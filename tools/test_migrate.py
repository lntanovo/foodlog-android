"""Public migration regressions using generated, synthetic data only."""
import hashlib
import json
import pathlib
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

from build_seed import make_seed
from migrate_legacy import convert, excel_date, item, number


def write_workbook(path):
    """Minimal XLSX: shared strings, cached formula, separate body date axis."""
    ns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'
    rel='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    tag=lambda name: '{'+ns+'}'+name
    workbook=ET.Element(tag('workbook'))
    sheets=ET.SubElement(workbook,tag('sheets'))
    ET.SubElement(sheets,tag('sheet'),{'name':'每日记录','sheetId':'1','{'+rel+'}id':'rId1'})
    worksheet=ET.Element(tag('worksheet'))
    data=ET.SubElement(worksheet,tag('sheetData'))
    rows={}

    def cell(ref,value,kind='inlineStr',formula=None):
        row_number=''.join(c for c in ref if c.isdigit())
        if row_number not in rows:
            rows[row_number]=ET.SubElement(data,tag('row'),{'r':row_number})
        attrs={'r':ref}
        if kind: attrs['t']=kind
        c=ET.SubElement(rows[row_number],tag('c'),attrs)
        if formula is not None:
            ET.SubElement(c,tag('f'),{'t':'shared','si':'0'}).text=formula
        if kind=='inlineStr':
            ET.SubElement(ET.SubElement(c,tag('is')),tag('t')).text=str(value)
        else:
            ET.SubElement(c,tag('v')).text=str(value)

    for ref,value in {
        'A1':'2024-02-01','H1':'日期','I1':'晨重 kg',
        'A2':'午餐','H2':'2024-02-02','I2':80,'L2':'休息',
        'A3':'食品','B3':'份量（g）',
        'A4':'合成谷物','B4':50,
        'A5':'合成谷物','B5':'待补',
        'A6':'全天合计',
        'A7':'2024-02-02','H7':'2024-02-01','I7':81,'L7':'力量',
        'A8':'晚餐','A9':'食品','B9':'份量（g）',
        'A10':'合成谷物','B10':25,
    }.items():
        if value=='合成谷物': cell(ref,0,'s')
        else: cell(ref,value,None if isinstance(value,int) else 'inlineStr')
    cell('C6',999,None,'SUM(C4:C5)')
    with zipfile.ZipFile(path,'w') as z:
        z.writestr('xl/workbook.xml',ET.tostring(workbook,encoding='utf-8'))
        z.writestr('xl/_rels/workbook.xml.rels',
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Target="worksheets/sheet1.xml" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/>'
            '</Relationships>')
        z.writestr('xl/worksheets/sheet1.xml',ET.tostring(worksheet,encoding='utf-8'))
        z.writestr('xl/sharedStrings.xml','<sst xmlns="'+ns+'"><si><t>合成谷物</t></si></sst>')


class MigrationTests(unittest.TestCase):
    def test_date_systems(self):
        self.assertEqual('2024-01-02',excel_date('45293'))
        self.assertEqual('2024-01-02',excel_date(str(45293-1462),True))
        self.assertEqual('2024-02-03',excel_date('2024-02-03T12:00:00'))

    def test_unknown_and_zero(self):
        self.assertIsNone(number('待补'))
        self.assertIsNone(number(None))
        self.assertEqual('0',number('0'))
        for s in ('NaN','-1','Infinity','1e9'):
            with self.assertRaises(ValueError): number(s)

    def test_synthetic_sources_and_stable_keys(self):
        with tempfile.TemporaryDirectory(prefix='foodlog-migration-') as folder:
            root=pathlib.Path(folder)
            xlsx,records,foods,targets=[root/name for name in ('sample.xlsx','records.json','foods.json','targets.json')]
            write_workbook(xlsx)
            foods.write_text(json.dumps({'foods':[{
                'id':'synthetic_grain','name':'合成谷物','source':'Synthetic test fixture',
                'per_100g':{'energy_kj':1000,'protein_g':10,'carbohydrate_g':60,
                    'fat_g':2,'sodium_mg':0,'dietary_fiber_g':5},
            }]}),encoding='utf-8')
            records.write_text(json.dumps({'actual_food_intake':[
                {'date':'2024-02-01','food_id':'synthetic_grain','quantity_g':1000,'meal':'午餐'},
                {'date':'2024-02-03','food_id':'synthetic_grain','quantity_g':40,'meal':'加餐'},
            ],'meal_plans':[{'note':'Synthetic plan; must never become an eaten meal'}],
            'daily_summaries':[{'date':'2024-02-03'}]}),encoding='utf-8')
            targets.write_text(json.dumps({'effective_on':'2024-02-01','body_weight_kg':80,'targets':{
                'training':{'protein_g_per_kg':1.25,'carbohydrate_g_per_kg':3,'fat_g_per_kg':1},
                'rest':{'protein_g_per_kg':1.25,'carbohydrate_g_per_kg':2,'fat_g_per_kg':0.75},
            }}),encoding='utf-8')
            paths=(xlsx,records,foods,targets)
            hashes=[hashlib.sha256(p.read_bytes()).hexdigest() for p in paths]
            a=convert(*paths)
            self.assertEqual(a,convert(*paths))
            self.assertEqual(hashes,[hashlib.sha256(p.read_bytes()).hexdigest() for p in paths])
            self.assertEqual(1,len(a['foods']))
            self.assertEqual(3,len(a['meals']))
            items=[i for m in a['meals'] for i in m['items']]
            self.assertEqual(4,len(items))
            self.assertEqual(['50',None,'25','40'],[i['quantity'] for i in items])
            self.assertEqual('5',items[0]['derived']['protein'])
            self.assertEqual({'2024-02-01':'81','2024-02-02':'80'},
                {r['date']:r['weight'] for r in a['bodies']})
            self.assertEqual('1.25',a['plans'][0]['training']['protein'])
            self.assertTrue(any('共享公式' in line for line in a['report']))
            self.assertTrue(any('JSON实际明细已跳过' in line for line in a['report']))
            self.assertEqual(2,len(convert(xlsx=xlsx,foods_file=foods)['meals']))
            self.assertEqual(2,len(convert(records=records,foods_file=foods)['meals']))
            with self.assertRaises(ValueError): convert()

    def test_unknown_quantity_has_unknown_nutrients(self):
        seed=make_seed()
        self.assertEqual('FOOD_LIBRARY',seed['kind'])
        self.assertNotIn('meals',seed)
        self.assertNotIn('bodies',seed)
        v=seed['versions'][0]
        i=item(v,None,'synthetic-test')
        self.assertTrue(all(x is None for x in i['derived'].values()))
        self.assertEqual('UNKNOWN',i['quantityStatus'])
        seed['versions'].clear()
        self.assertTrue(make_seed()['versions'])  # Each fallback read is an independent copy.


if __name__=='__main__': unittest.main()
