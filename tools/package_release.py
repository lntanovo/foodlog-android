"""Package public source and APK separately from the personal import JSON."""
import fnmatch, hashlib, pathlib, shutil, zipfile

root=pathlib.Path(__file__).resolve().parents[1]
EXCLUDED_DIRS={'.git','.kotlin','.idea','build','.gradle','deliverables','__pycache__','logs'}
EXCLUDED_NAMES={'local.properties','.git'}  # .git can also be a worktree pointer file.
EXCLUDED_PATTERNS=(
    'current-*','*.log','*.jks','*.keystore','*.db','*.db-*','.env*',
    '*.sqlite','*.sqlite-*','*.sqlite3','*.sqlite3-*','*.pyc','*.iml',
)


def include_source(relative_path):
    """Keep public project files; exclude VCS metadata, caches and local data."""
    rel=pathlib.PurePath(relative_path)
    parts=tuple(part.casefold() for part in rel.parts)
    name=rel.name.casefold()
    return not (
        any(part in EXCLUDED_DIRS for part in parts)
        or name in EXCLUDED_NAMES
        or any(fnmatch.fnmatchcase(name,pattern) for pattern in EXCLUDED_PATTERNS)
    )


def source_files(base_dir=root):
    """Read-only candidate listing, also available for packaging checks."""
    return [f for f in sorted(base_dir.rglob('*'))
        if f.is_file() and include_source(f.relative_to(base_dir))]


def main():
    out=root/'deliverables';out.mkdir(exist_ok=True)
    apk=out/'食记-1.0.0-debug.apk'
    shutil.copyfile(root/'app/build/outputs/apk/debug/app-debug.apk',apk)
    source=out/'食记-1.0.0-Android源码.zip'
    with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for f in source_files():
            z.write(f,'android-app/'+f.relative_to(root).as_posix())
        # Preserve the repository's actual ignore rules, without duplicate entries.
        if not (root/'.gitignore').is_file():
            z.writestr('android-app/.gitignore',
                '.git/\n.gradle/\n.kotlin/\n.idea/\n**/build/\nlocal.properties\ndeliverables/\n'
                'logs/\ncurrent-*\n*.log\n*.jks\n*.keystore\n*.db\n*.db-*\n.env*\n__pycache__/\n*.pyc\n*.iml\n')
    files=[apk,source]
    (out/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in files),encoding='utf-8')
    with zipfile.ZipFile(apk) as z:
        assets=[n for n in z.namelist() if n.startswith('assets/')]
        assert assets==['assets/foods_seed_v1.json'],assets
        import json
        d=json.loads(z.read(assets[0]));assert len(d['foods'])==11
        assert all(not d.get(key) for key in ('meals','days','bodies','plans','targets','origins','templates'))
        assert not d.get('meta',{}).get('profile')
    print('Public APK assets verified: 11 foods; no private records.')
    for f in files:print(f, f.stat().st_size)


if __name__=='__main__': main()
