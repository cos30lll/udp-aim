#!/usr/bin/env python3
"""Check the source tree, local links, excluded artifacts and file manifest."""
from pathlib import Path
import argparse, ast, hashlib, json, re, sys
import xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[1]
SKIP={'build','dist','.git','.gradle','.idea','.vscode','__pycache__'}
BANNED={'.apk','.aab','.dll','.exe','.so','.o','.class','.dex','.p12','.pfx','.jks','.keystore','.pem','.key','.pcap','.pcapng','.log','.zip'}
MANIFEST='SOURCE_MANIFEST.json'
def files():
    return sorted(p for p in R.rglob('*') if p.is_file() and not any(x in SKIP for x in p.relative_to(R).parts))
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-manifest',action='store_true',help='Regenerate after reviewing intentional changes.')
    args=parser.parse_args()
    errors=[]; paths=files(); hashes={}
    for p in paths:
        rel=p.relative_to(R).as_posix()
        if p.is_symlink(): errors.append('symlink: '+rel)
        if p.suffix.lower() in BANNED or p.name.endswith(('.jsonl','.jsonl.txt')) or p.name.startswith('.env'):
            errors.append('private or generated artifact: '+rel)
        data=p.read_bytes()
        if p.suffix != '.py' and any(marker in data for marker in [b'-----BEGIN PRIVATE KEY-----', b'-----BEGIN RSA PRIVATE KEY-----']):
            errors.append('private key content: '+rel)
        if rel!=MANIFEST: hashes[rel]=hashlib.sha256(data).hexdigest()
        try:
            if p.suffix=='.py': ast.parse(data.decode('utf-8'),filename=rel)
            if p.suffix=='.json' and rel!=MANIFEST: json.loads(data)
            if p.suffix in {'.xml','.csproj'}: ET.fromstring(data)
            if p.suffix=='.md' and 'third_party' not in p.relative_to(R).parts:
                text=data.decode('utf-8')
                for link in re.findall(r'\[[^\]]*\]\(([^)]+)\)',text):
                    target=link.split('#',1)[0]
                    if not target or ':' in target or target.startswith('//'): continue
                    if not (p.parent/target).exists(): errors.append('broken link: '+rel+' -> '+link)
        except (SyntaxError,ValueError,UnicodeError,ET.ParseError) as e:
            errors.append(rel+': '+str(e))
    j=R/'vpn/java/local/udp/esp/vpn'
    for p in j.glob('*.java'):
        text=p.read_text(encoding='utf-8')
        for token in ['RunLog','RotationDiagnostics','rotationReport','logStatus','MediaStore','android.util.Log']:
            if token in text: errors.append('diagnostic reference: '+p.name+' '+token)
    manifest=R/'vpn/AndroidManifest.xml'
    if 'WRITE_EXTERNAL_STORAGE' in manifest.read_text(encoding='utf-8'): errors.append('obsolete log storage permission')
    for rel in ['README.md','LICENSE','NOTICE','THIRD_PARTY.md','AGENTS.md','docs/FROM_ZERO.md','docs/BUILD.md','docs/PROTOCOL.md','docs/VALIDATION.md','tools/build_android.py','tools/il/README.md']:
        if not (R/rel).is_file(): errors.append('missing required file: '+rel)
    if errors:
        print(json.dumps({'status':'failed','errors':errors},ensure_ascii=False,indent=2));return 1
    if args.write_manifest:
        (R/MANIFEST).write_text(json.dumps({'schema':1,'sha256':hashes},ensure_ascii=False,indent=2)+chr(10),encoding='utf-8',newline='\n')
    if not (R/MANIFEST).exists():
        print('Missing SOURCE_MANIFEST.json; review changes and run --write-manifest.');return 1
    recorded=json.loads((R/MANIFEST).read_text(encoding='utf-8'))['sha256']
    if recorded!=hashes:
        print(json.dumps({'status':'failed','manifest_mismatch':sorted(k for k in set(recorded)|set(hashes) if recorded.get(k)!=hashes.get(k))},ensure_ascii=False));return 1
    print(json.dumps({'status':'passed','files':len(paths),'manifest_entries':len(hashes),'raw_samples_included':False,'runtime_tested':False},ensure_ascii=False));return 0
if __name__=='__main__': sys.exit(main())
