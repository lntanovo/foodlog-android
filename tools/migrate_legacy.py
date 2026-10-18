"""Read-only XLSX/legacy JSON -> FoodLog v1 import. Python 3.10+, standard library only.

Never executes formulas. Reads worksheet relationships/shared strings/formula caches,
discovers column headers, and reconciles independently from the visible total cells.
Provide --xlsx and/or --json explicitly; optional --foods and --targets are also
explicit inputs. Without --foods the public bundled food library is used.
"""
import argparse, copy, hashlib, json, pathlib, posixpath, re, uuid, zipfile
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, localcontext
import xml.etree.ElementTree as ET
from build_seed import make_seed, identity, STAMP

NS={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
DATASET_NAMESPACE='foodlog-personal-legacy-v1'
FIELDS=['kcal','protein','carbs','fat','sodium','fiber']
def canonical(n):
    s=format(Decimal(n),'f')
    return s.rstrip('0').rstrip('.') if '.' in s else s
def number(value, positive=False):
    if value is None or str(value).strip() in ('','待补','待补齐','未知'): return None
    s=str(value).strip()
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?',s): raise ValueError('非法数量: '+s)
    n=Decimal(s)
    if not n.is_finite() or n<0 or (positive and n<=0): raise ValueError('非法数值: '+s)
    # Excel persists binary doubles with 17 digits. Retain its meaningful 15 digits.
    with localcontext() as c:
        c.prec=15
        return canonical(+n)
def column(s):
    n=0
    for c in s: n=n*26+ord(c)-64
    return n
def read_xlsx(path):
    sheets={}; report=[]
    with zipfile.ZipFile(path) as z:
        wb=ET.fromstring(z.read('xl/workbook.xml'))
        prop=wb.find('m:workbookPr',NS)
        epoch1904=prop is not None and prop.get('date1904','0') in ('1','true')
        rels={r.get('Id'):r.get('Target') for r in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
        strings=[]
        if 'xl/sharedStrings.xml' in z.namelist(): strings=[''.join(x.itertext()) for x in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si',NS)]
        for sh in wb.find('m:sheets',NS):
            target=rels[sh.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')]
            target=target.lstrip('/') if target.startswith('/') else posixpath.normpath('xl/'+target)
            tree=ET.fromstring(z.read(target));rows=defaultdict(dict);shared=0
            for cell in tree.findall('.//m:sheetData/m:row/m:c',NS):
                ref=cell.get('r');match=re.fullmatch(r'([A-Z]+)(\d+)',ref)
                if not match: continue
                v=cell.find('m:v',NS);f=cell.find('m:f',NS);kind=cell.get('t');value=v.text if v is not None else None
                if kind=='s' and value is not None: value=strings[int(value)]
                elif kind=='inlineStr': value=''.join(cell.find('m:is',NS).itertext())
                if f is not None and f.get('t')=='shared': shared+=1
                rows[int(match[2])][column(match[1])]={'value':value,'formula':f is not None,'type':kind,'ref':ref}
            sheets[sh.get('name')]=rows
            if shared: report.append(f'{sh.get("name")}：检测到{shared}个共享公式单元格；未执行公式，营养按食品源重新核算。')
    return sheets,epoch1904,report
def value(row,col): return row.get(col,{}).get('value')
def excel_date(value_,epoch1904=False):
    if value_ is None: return None
    s=str(value_)
    if re.fullmatch(r'\d{4}-\d{2}-\d{2}(?:T.*)?',s): return date.fromisoformat(s[:10]).isoformat()
    if re.fullmatch(r'\d+(?:\.\d+)?',s) and Decimal(s)>10000:
        serial=int(Decimal(s))
        if epoch1904: base=date(1904,1,1)
        else: base=date(1899,12,30) if serial>=60 else date(1899,12,31)
        return (base+timedelta(days=serial)).isoformat()
    return None
def training(text):
    text=text or ''
    if '力量' in text and '羽毛球' in text: return 'BOTH'
    if '力量' in text: return 'STRENGTH'
    if '羽毛球' in text: return 'BADMINTON'
    if '休息' in text: return 'REST'
    return 'UNKNOWN'
def energy(v):
    with localcontext() as c:
        c.prec=34
        if v.get('labelKcal') is not None: return Decimal(v['labelKcal'])
        if v.get('labelKj') is not None: return Decimal(v['labelKj'])/Decimal('4.184')
        if v.get('tableKcal') is not None: return Decimal(v['tableKcal'])
        n=v['nutrients']
        if all(n.get(k) is not None for k in ['protein','carbs','fat']): return Decimal(n['protein'])*4+Decimal(n['carbs'])*4+Decimal(n['fat'])*9
        return None
def item(v,qty,key):
    nutrients=dict.fromkeys(FIELDS)
    if qty is not None:
        with localcontext() as ctx:
            ctx.prec=34
            factor=Decimal(qty)/Decimal(v['basisQuantity'])
            for k in FIELDS:
                n=energy(v) if k=='kcal' else v['nutrients'].get(k)
                # Kotlin stores full product (ratio and energy each use DECIMAL128).
                if n is not None:
                    with localcontext() as product:
                        product.prec=100
                        nutrients[k]=canonical(Decimal(n)*factor)
    return dict(id=identity('item/'+key),foodId=v['foodId'],foodVersionId=v['id'],quantity=qty,unit='G',quantityStatus='UNKNOWN' if qty is None else 'KNOWN',snapshot=v,normalizedAmount=qty,derived=nutrients)
def fingerprint(obj): return hashlib.sha256(json.dumps(obj,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
def convert(xlsx=None,records=None,foods_file=None,targets_file=None):
    if xlsx is None and records is None: raise ValueError('请提供 --xlsx 或 --json 输入文件')
    out=make_seed(foods_file);out['kind']='LEGACY_IMPORT';out['meta']={'datasetId':identity('personal-legacy'),'timezone':'Asia/Shanghai'}
    sheets,epoch,report=read_xlsx(xlsx) if xlsx is not None else ({},False,[])
    report.insert(0,'历史营养快照按导入时可确认食品资料重建，不能追溯不可得的原始标签版本。')
    foods=out['foods'];versions=out['versions'];aliases={v['name']:v for v in versions}
    egg=next((v for v in versions if v['name'].startswith('水煮全蛋')),None)
    if egg is not None: aliases['水煮蛋']=egg
    lookup=sheets.get('食品速查',{})
    for r,row in sorted(lookup.items()):
        if value(row,1)=='食品' and '热量' in str(value(row,2)) and '蛋白质' in str(value(row,3)):
            for rr in range(r+1,max(lookup,default=r)+1):
                vals=lookup.get(rr,{})
                name=value(vals,1)
                if not name: break
                if name in aliases: continue
                ns=[number(value(vals,c)) for c in range(2,8)]
                if all(n is None for n in ns): continue
                sid='custom/'+str(name);fid=identity('food/'+sid);vid=identity('version/'+sid+'/'+fingerprint(ns))
                v=dict(id=vid,foodId=fid,versionNo=1,name=name,basisQuantity='100',basisUnit='G',labelKcal=ns[0],nutrients=dict(zip(FIELDS,ns))|{'kcal':None},sourceKind='USER_INPUT',sourceText='旧Excel食品速查自定义资料；导入时重建',weighingBasis=value(vals,8) or '每100g可食部',createdAt=STAMP)
                foods.append(dict(id=fid,activeVersionId=vid,createdAt=STAMP,updatedAt=STAMP));versions.append(v);aliases[name]=v
    rows=sheets.get('每日记录',{})
    if xlsx is not None and '每日记录' not in sheets: raise ValueError('未找到每日记录工作表')
    meals_by_key={};bodies={};days={};source_items={};ordinal=defaultdict(int);excel_days=set();notes=defaultdict(list);cached={}
    current_date=None;meal_type=None;food_col=None;qty_col=None
    def day(d): return days.setdefault(d,dict(date=d,training='UNKNOWN',loggingStatus='UNKNOWN',note=''))
    # Independent right-hand date axis, located by the weight header, never left-row alignment.
    for r,row in sorted(rows.items()):
        for col,cell in row.items():
            if cell['value']=='日期' and '晨重' in str(value(row,col+1)):
                for rr in range(r+1,max(rows)+1):
                    right=rows.get(rr,{})
                    d=excel_date(value(right,col),epoch)
                    if d is None: continue
                    w=number(value(right,col+1),True)
                    if w is not None:
                        if d in bodies: report.append(f'冲突：右侧{d}重复晨重，采用最后填写值。')
                        bodies[d]=dict(date=d,weight=w,standard=True,note='从Excel晨起空腹体重区导入')
                    tr=training(value(right,col+4));nt=value(right,col+5)
                    if tr!='UNKNOWN' or nt:
                        day(d)['training']=tr
                        if nt: notes[d].append(str(nt))
    for r,row in sorted(rows.items()):
        first=value(row,1)
        # Only the left-hand date is the meal date.
        d=excel_date(first,epoch)
        if d:
            current_date=d;meal_type=None;food_col=None;excel_days.add(d)
            tr=training(value(row,4))
            if tr!='UNKNOWN': day(d)['training']=tr
            continue
        if current_date is None: continue
        if first in ('午餐','晚餐','早餐','加餐','早餐/加餐'):
            meal_type='加餐' if first=='早餐/加餐' else first;food_col=None;continue
        if first=='食品':
            candidates=[c for c in row if '份量' in str(value(row,c))]
            if candidates: food_col=1;qty_col=candidates[0]
            continue
        if first and ('合计' in str(first) or '总计' in str(first)):
            if '全天' in str(first):
                raw=value(row,3)
                if raw is not None and re.fullmatch(r'[0-9.]+',raw): cached[current_date]=Decimal(raw)
            food_col=None;continue
        if first=='饮食备注':
            if value(row,2): notes[current_date].append(str(value(row,2)))
            continue
        if first and ('鸭腿的骨头' in str(first) or '骨重' in str(first)):
            notes[current_date].append(str(first));report.append(f'{current_date}：保留鸭腿原填份量和骨重备注，未自动纠正。')
        if food_col is None or meal_type is None: continue
        name=value(row,food_col);raw=value(row,qty_col)
        if not name or raw in (None,''): continue  # printed food labels are not evidence of eating
        qty=number(raw)
        if qty=='0': continue
        if name not in aliases: raise ValueError(f'{current_date} 未识别实际食品“{name}”，请补充食品资料后再迁移；源文件未改动。')
        v=aliases[name];ordinal[(current_date,meal_type,v['foodId'])]+=1
        source_key=f"{current_date}/{meal_type}/{v['foodId']}/{ordinal[(current_date,meal_type,v['foodId'])]}"
        meal_key=(current_date,meal_type)
        meal=meals_by_key.setdefault(meal_key,dict(id=identity('meal/'+current_date+'/'+meal_type),date=current_date,type=meal_type,status='POSTED',items=[],createdAt=STAMP,updatedAt=STAMP))
        it=item(v,qty,source_key);meal['items'].append(it);source_items[it['id']]=source_key
        day(current_date)['loggingStatus']='PARTIAL'
    # Actual JSON details only, with Excel taking priority on overlapping dates.
    legacy=json.loads(pathlib.Path(records).read_text(encoding='utf-8')) if records is not None else {}
    by_seed={f.get('seedId'):next(v for v in versions if v['id']==f['activeVersionId']) for f in foods if f.get('seedId')}
    for rec in legacy.get('actual_food_intake',[]):
        d=rec['date']
        if d in excel_days:
            report.append(f'冲突：{d} JSON实际明细已跳过，采用最新Excel。');continue
        if rec.get('food_id') not in by_seed: raise ValueError('JSON存在未知实际食品:'+str(rec.get('food_id')))
        v=by_seed[rec['food_id']];qty=number(rec.get('quantity_g'));mt=rec.get('meal','加餐')
        if qty=='0':continue
        ordinal[(d,mt,v['foodId'])]+=1;key=f"{d}/{mt}/{v['foodId']}/{ordinal[(d,mt,v['foodId'])]}"
        m=meals_by_key.setdefault((d,mt),dict(id=identity('meal/'+d+'/'+mt),date=d,type=mt,status='POSTED',items=[],createdAt=STAMP,updatedAt=STAMP))
        it=item(v,qty,key);m['items'].append(it);source_items[it['id']]=key
        if rec.get('note'): notes[d].append(rec['note'])
        day(d)['loggingStatus']='PARTIAL'
    for d,ns in notes.items(): day(d)['note']='；'.join(dict.fromkeys(ns))
    meals=list(meals_by_key.values())
    for m in meals: m['note']='按导入时食品资料重建；'+days.get(m['date'],{}).get('note','')
    for d,cache in cached.items():
        vals=[Decimal(i['derived']['kcal']) for m in meals if m['date']==d for i in m['items'] if i['derived']['kcal'] is not None]
        if vals:
            total=sum(vals);report.append(f'{d}：重算已知热量{total:.6f} kcal；Excel缓存{cache:.6f} kcal；差额{total-cache:.6f} kcal（不把合计再次入账）。')
    plans=[]
    if targets_file is not None:
        target=json.loads(pathlib.Path(targets_file).read_text(encoding='utf-8'))
        # Current explicitly confirmed legacy reference, not withdrawn target_history.
        ref=target
        source=ref.get('targets',{})
        if all(k in source for k in ('training','rest')):
            def macros(k):
                s=source[k];return dict(protein=str(s['protein_g_per_kg']),carbs=str(s['carbohydrate_g_per_kg']),fat=str(s['fat_g_per_kg']))
            plans=[dict(id=identity('personal-plan/'+ref['effective_on']),effectiveFrom=ref['effective_on'],mode='PER_KG',referenceWeight=str(ref['body_weight_kg']),training=macros('training'),rest=macros('rest'),createdAt=STAMP)]
    origins=[]
    def origin(key,entity,payload): origins.append(dict(namespace=DATASET_NAMESPACE,key=key,entityId=entity,fingerprint=fingerprint(payload),importedAt=STAMP))
    for m in meals:
        for i in m['items']: origin(source_items[i['id']],m['id'],{'item':i,'mealNote':m['note']})
    for b in bodies.values(): origin('body/'+b['date'],b['date'],b)
    for d in days.values(): origin('day/'+d['date'],d['date'],{'day':d,'meals':[m for m in meals if m['date']==d['date']]})
    for p in plans: origin('plan/'+p['effectiveFrom'],p['id'],p)
    report.append(f'跳过{len(legacy.get("meal_plans",[]))}份建议补餐/草案、{len(legacy.get("daily_summaries",[]))}份时点汇总及已撤回历史目标。')
    report.append(f'食品{len(foods)}种，餐食{len(meals)}餐，明细{sum(len(m["items"]) for m in meals)}项，晨重{len(bodies)}条，份量待补{sum(i["quantity"] is None for m in meals for i in m["items"])}项。')
    out.update(meals=meals,bodies=list(bodies.values()),days=list(days.values()),plans=plans,origins=origins,report=report)
    return out
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--xlsx',type=pathlib.Path,help='Legacy workbook with a 每日记录 sheet (optional if --json is provided)')
    p.add_argument('--json','--records',dest='records',type=pathlib.Path,help='Legacy actual_food_intake JSON (optional if --xlsx is provided)')
    p.add_argument('--foods',type=pathlib.Path,help='Original food-label JSON; omitted: public bundled food library')
    p.add_argument('--targets',type=pathlib.Path,help='Optional legacy reference-target JSON')
    p.add_argument('--out',type=pathlib.Path,required=True,help='Destination personal import JSON; keep it private')
    args=p.parse_args()
    if args.xlsx is None and args.records is None: p.error('请提供 --xlsx 或 --json 输入文件')
    inputs=[path for path in (args.xlsx,args.records,args.foods,args.targets) if path is not None]
    for path in inputs:
        if not path.is_file(): p.error('输入文件不存在: '+str(path))
    report_path=args.out.with_suffix('.report.md')
    if any(path.resolve() in (args.out.resolve(),report_path.resolve()) for path in inputs):
        p.error('输出文件和报告不能覆盖任何输入文件')
    before={path:hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
    output=convert(args.xlsx,args.records,args.foods,args.targets)
    assert before=={path:hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs},'源文件发生变化'
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
    source_hashes='\n'.join('- '+path.name+' SHA-256: `'+digest+'`' for path,digest in before.items())
    report_path.write_text('# 旧数据迁移核对报告\n\n'+source_hashes+'\n\n'+'\n\n'.join(output['report'])+'\n\n个人导入文件仅供本人使用，未打入APK。\n',encoding='utf-8')
    print('\n'.join(output['report']));print(args.out)
if __name__=='__main__': main()
