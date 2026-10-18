"""Download isolated build tools; no global PATH/system changes."""
import concurrent.futures, hashlib, json, pathlib, urllib.request, zipfile
root = pathlib.Path(__file__).resolve().parents[2] / '.android-toolchain'
proxy = urllib.request.ProxyHandler({'http': 'http://127.0.0.1:7897', 'https': 'http://127.0.0.1:7897'})
opener = urllib.request.build_opener(proxy)
root.mkdir(parents=True,exist_ok=True)
jobs = [
    ('jdk.zip', 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip', 'jdk'),
    ('sdk.zip', 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip', 'sdk/cmdline-tools/latest'),
    ('gradle.zip', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', 'gradle'),
]
def fetch(job):
    name, url, dest = job
    path = root / name
    if not path.exists():
        with opener.open(url, timeout=90) as response, path.open('wb') as out:
            while chunk := response.read(1024 * 1024): out.write(chunk)
    print(name, path.stat().st_size, flush=True)
    expected={'jdk.zip':'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0','gradle.zip':'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'}.get(name)
    if expected and hashlib.sha256(path.read_bytes()).hexdigest()!=expected: raise ValueError('Checksum mismatch: '+name)
    target = root / dest
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as z:
        if name == 'sdk.zip':
            for info in z.infolist():
                if info.filename.startswith('cmdline-tools/'):
                    info.filename = info.filename[len('cmdline-tools/'):]
                    if info.filename: z.extract(info, target)
        else: z.extractall(target)
    print('Ready:', dest, flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(fetch, jobs))
