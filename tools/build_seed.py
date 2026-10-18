"""Read the public food library, or explicitly rebuild it from label source JSON.

Running without --source uses the bundled public asset and never reads a user's
adjacent nutrition folder. Personal meals, weights and targets are not seed data.
"""
import argparse, json, uuid, pathlib
APP_ROOT=pathlib.Path(__file__).resolve().parents[1]
PUBLIC_SEED=APP_ROOT/'app/src/main/assets/foods_seed_v1.json'
# Public, stable namespace: keep food IDs compatible with existing installations.
NAMESPACE=uuid.UUID('8829b5de-132d-430e-91e0-b5c60d823aaf')
def identity(key): return str(uuid.uuid5(NAMESPACE,key))
STAMP='2026-10-18T00:00:00Z'
def make_seed(source_file=None):
    if source_file is None:
        # Parsing on every call gives callers an independent copy to extend.
        seed=json.loads(PUBLIC_SEED.read_text(encoding='utf-8'))
        if seed.get('kind')!='FOOD_LIBRARY' or not isinstance(seed.get('foods'),list) or not isinstance(seed.get('versions'),list):
            raise ValueError('公开食品种子格式不正确')
        return seed
    raw=json.loads(pathlib.Path(source_file).read_text(encoding='utf-8'))
    foods=[]; versions=[]
    for source in raw['foods']:
        sid=source['id']; fid=identity('food/'+sid); vid=identity('version/'+sid+'/1')
        milk=sid=='eurasia_whole_milk_250g'; values=source['per_box' if milk else 'per_100g']
        estimate=source.get('is_estimate',False)
        portions=[]
        for key,name in [('default_bag_g','袋'),('default_box_g','盒'),('default_unit_g','个')]:
            if source.get(key): portions.append(dict(name=name,amount=str(source[key]),unit='G',estimated=estimate))
        def val(key): return str(values[key]) if key in values else None
        foods.append(dict(id=fid,seedId=sid,activeVersionId=vid,createdAt=STAMP,updatedAt=STAMP))
        versions.append(dict(id=vid,foodId=fid,versionNo=1,name=source['name'],basisQuantity='250' if milk else '100',basisUnit='G',
            labelKcal=val('energy_kcal') if sid=='myprotein_chocolate_powder' else None,
            labelKj=None if estimate else val('energy_kj'),tableKcal=val('energy_kcal') if estimate else None,
            nutrients=dict(protein=val('protein_g'),carbs=val('carbohydrate_g'),fat=val('fat_g'),sodium=val('sodium_mg'),fiber=val('dietary_fiber_g')),
            portions=portions,sourceKind='AVERAGE_ESTIMATE' if estimate else 'PACKAGE_LABEL',
            sourceText=source['source']+'；'+source.get('notes',''),sourceUrl=source.get('source_url',''),weighingBasis=source.get('basis','实际可食重量'),
            estimatedFields=['sodium'] if sid=='myprotein_chocolate_powder' else (['all'] if estimate else []),createdAt=STAMP))
    return dict(schemaVersion=1,kind='FOOD_LIBRARY',exportedAt=STAMP,meta=dict(datasetId=identity('public-seed')),foods=foods,versions=versions)
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=pathlib.Path,help='Explicit original food-label JSON; omitted: copy the public bundled seed')
    parser.add_argument('--out',type=pathlib.Path,default=PUBLIC_SEED,help='Destination food-library JSON')
    args=parser.parse_args()
    if args.source and args.source.resolve()==args.out.resolve():
        parser.error('--source 和 --out 不能是同一个文件')
    seed=make_seed(args.source)
    out=args.out;out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(seed,ensure_ascii=False,indent=2),encoding='utf-8')
    print(out)
