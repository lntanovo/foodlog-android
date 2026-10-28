"""Seed synthetic records ONLY in the explicitly named isolated regression AVD."""
import argparse
import json
import pathlib
import sqlite3
import subprocess
import tempfile
import time
import uuid

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
a = p.parse_args()
def adb(*args, data=None, check=True):
    return subprocess.run([a.adb, '-s', a.serial, *args], input=data, capture_output=True, check=check).stdout
assert 'FoodLogQuotaTest' in adb('emu', 'avd', 'name').decode()
adb('shell', 'am', 'start', '-W', '-n', 'com.foodlog.app/.MainActivity')
time.sleep(3)
adb('shell', 'am', 'force-stop', 'com.foodlog.app')
with tempfile.TemporaryDirectory() as temp:
    path = pathlib.Path(temp) / 'foodlog.db'
    path.write_bytes(adb('exec-out', 'run-as', 'com.foodlog.app', 'cat', 'databases/foodlog.db'))
    for suffix in ('-wal', '-shm'):
        data = adb('exec-out', 'run-as', 'com.foodlog.app', 'cat', 'databases/foodlog.db' + suffix, check=False)
        if data:
            pathlib.Path(str(path) + suffix).write_bytes(data)
    db = sqlite3.connect(path)
    version = json.loads(db.execute('SELECT json FROM food_versions LIMIT 1').fetchone()[0])
    stamp = '2026-10-01T00:00:00Z'
    identity = lambda: str(uuid.uuid4())
    item = dict(id=identity(), foodId=version['foodId'], foodVersionId=version['id'], quantity=None,
                unit=version.get('basisUnit', 'G'), quantityStatus='UNKNOWN', snapshot=version,
                normalizedAmount=None, derived={})
    meals = [dict(id=identity(), date='2026-10-01', type='午餐', status='POSTED', items=[item], note='synthetic upgrade fixture', createdAt=stamp, updatedAt=stamp),
             dict(id=identity(), date='2026-10-02', type='晚餐', status='DRAFT', items=[dict(item, id=identity())], note='synthetic draft', createdAt=stamp, updatedAt=stamp)]
    plan = dict(id=identity(), effectiveFrom='2026-10-01', mode='DIRECT_MACROS', training=dict(protein='100', carbs='200', fat='60'), rest=dict(protein='100', carbs='180', fat='60'), createdAt=stamp)
    fixtures = {
        'meals': [('id', m) for m in meals],
        'body_records': [('date', dict(date=f'2026-10-0{i}', weight=str(70+i/10), standard=i != 4)) for i in range(1,5)],
        'day_logs': [('date', dict(date='2026-10-01', training='REST', dietDayType='REST', loggingStatus='COMPLETE'))],
        'target_plans': [('id', plan)],
        'day_targets': [('date', dict(date='2026-10-01', planId=plan['id'], dayType='REST', nutrients=dict(kcal='1660',protein='100',carbs='180',fat='60')))],
        'meal_templates': [('id', dict(id=identity(), name='Synthetic template', type='午餐', items=[dict(item, id=identity())], createdAt=stamp, updatedAt=stamp))]
    }
    for table, rows in fixtures.items():
        db.execute('DELETE FROM ' + table)
        for key, value in rows:
            db.execute(f'INSERT INTO {table} ({key},json) VALUES (?,?)', (value[key],json.dumps(value,ensure_ascii=False)))
    db.execute('DELETE FROM import_origins')
    origin = dict(namespace='synthetic-upgrade', key='meal-1', entityId=meals[0]['id'], fingerprint='0'*64, importedAt=stamp)
    db.execute('INSERT INTO import_origins (namespace,sourceKey,json) VALUES (?,?,?)',
               (origin['namespace'], origin['key'], json.dumps(origin)))
    meta=dict(datasetId=identity(), timezone='Asia/Shanghai', profile=dict(name='Synthetic upgrade test'), revision=10)
    db.execute('UPDATE metadata SET json=?', (json.dumps(meta),))
    db.commit()
    clean = pathlib.Path(temp) / 'fixture.db'
    target = sqlite3.connect(clean)
    db.backup(target)
    assert target.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    target.execute('PRAGMA journal_mode=DELETE')
    target.close()
    db.close()
    adb('push', str(clean), '/data/local/tmp/foodlog-upgrade-fixture.db')
    adb('shell','run-as','com.foodlog.app','rm','-f','databases/foodlog.db-wal','databases/foodlog.db-shm')
    adb('shell','run-as','com.foodlog.app','cp','/data/local/tmp/foodlog-upgrade-fixture.db','databases/foodlog.db')
    assert clean.read_bytes() == adb('exec-out','run-as','com.foodlog.app','cat','databases/foodlog.db')
print('Synthetic fixture seeded in FoodLogQuotaTest: 2 meals, 4 body records, plan, target and template.')
