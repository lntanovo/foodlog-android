"""Check existing emulator test records across force-stop and version-code upgrade."""
import argparse, hashlib, json, pathlib, re, sqlite3, subprocess, tempfile, time

def main():
    p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--apk',required=True);p.add_argument('--report',required=True);a=p.parse_args()
    def adb(*args,check=True):
        return subprocess.run([a.adb,*args],capture_output=True,check=check).stdout
    def snap():
        adb('shell','am','force-stop','com.foodlog.app')
        with tempfile.TemporaryDirectory() as t:
            path=pathlib.Path(t)/'foodlog.db'
            path.write_bytes(adb('exec-out','run-as','com.foodlog.app','cat','databases/foodlog.db'))
            for suffix in ('-wal','-shm'):
                data=adb('exec-out','run-as','com.foodlog.app','cat','databases/foodlog.db'+suffix,check=False)
                if data: pathlib.Path(str(path)+suffix).write_bytes(data)
            db=sqlite3.connect(path)
            try:
                tables=['foods','food_versions','meals','day_logs','body_records','target_plans','day_targets','import_origins','metadata']
                result={table:sorted([json.loads(row[0]) for row in db.execute('SELECT json FROM '+table)],key=lambda x:json.dumps(x,sort_keys=True)) for table in tables}
                schema=db.execute('PRAGMA user_version').fetchone()[0]
                table_names={row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                templates=db.execute('SELECT count(*) FROM meal_templates').fetchone()[0] if 'meal_templates' in table_names else None
            finally: db.close()
            return result,schema,templates
    def version():
        text=adb('shell','dumpsys','package','com.foodlog.app').decode(errors='replace')
        return int(re.search(r'versionCode=(\d+)',text)[1])
    before_code=version();before,before_schema,before_templates=snap()
    assert before['meals'],'Need a persisted fixture meal before upgrade'
    adb('shell','am','start','-W','-n','com.foodlog.app/.MainActivity');time.sleep(2)
    restarted,restarted_schema,restarted_templates=snap();assert before==restarted,'Force-stop/restart changed records'
    install=adb('install','-r',a.apk).decode();assert 'Success' in install,install
    after_code=version();assert after_code>before_code,(before_code,after_code)
    adb('shell','am','start','-W','-n','com.foodlog.app/.MainActivity');time.sleep(2)
    after,after_schema,after_templates=snap();assert before==after,'Upgrade changed business records'
    assert after_schema==2,(before_schema,after_schema)
    assert after_templates==(before_templates or 0),'Migration changed template records'
    payload=json.dumps(after,ensure_ascii=False,sort_keys=True).encode()
    report=dict(beforeVersionCode=before_code,afterVersionCode=after_code,beforeRoomVersion=before_schema,afterRoomVersion=after_schema,templatesTableCreated=after_templates is not None,templateCount=after_templates,forceStopRestartPreserved=True,sameSignatureUpgradePreserved=True,counts={t:len(v) for t,v in after.items()},logicalDataSha256=hashlib.sha256(payload).hexdigest())
    pathlib.Path(a.report).write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
