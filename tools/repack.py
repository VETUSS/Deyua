#!/usr/bin/env python3
"""repack.py -- swap a freshly built engine .so into a built APK.

The manifest uses extractNativeLibs=false, so EVERY native lib must be
ZIP_STORED (uncompressed) and page-aligned in the archive.  This script only
guarantees STORED; pack.sh runs `zipalign -p 4` afterwards for the alignment,
then re-signs with apksigner.

  DEYVA_ROOT=/path/to/Deyva python repack.py --out out/unsigned.apk

DEYVA_ROOT defaults to the parent of this script's directory (the repo root).
The default --arm / --x86 point at engine builds produced by
upstream/engine/build.sh; pass your own paths if you build elsewhere.
"""
import argparse, os, sys, zipfile

ROOT = os.environ.get('DEYVA_ROOT') or os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))

ap = argparse.ArgumentParser()
ap.add_argument('--apk',   default=os.path.join(ROOT, 'android/app/build/outputs/apk/debug/app-debug.apk'))
ap.add_argument('--out',   required=True)
ap.add_argument('--arm',   default=os.path.join(ROOT, 'upstream/build/android-arm64-v8a/engine/spektrafilm_engine.so'))
ap.add_argument('--x86',   default=os.path.join(ROOT, 'upstream/build/android-x86_64/engine/spektrafilm_engine.so'))
ap.add_argument('--require', action='append', default=[], help='string that MUST exist in the new .so')
ap.add_argument('--forbid',  action='append', default=[], help='string that must NOT exist in the new .so')
a = ap.parse_args()

repl = {
    'lib/arm64-v8a/spektrafilm_engine.so': a.arm,
    'lib/x86_64/spektrafilm_engine.so':    a.x86,
}

for name, path in repl.items():
    if not os.path.exists(path):
        sys.exit('MISSING engine: ' + path)
    data = open(path, 'rb').read()
    for s in a.require:
        if s.encode() not in data:
            sys.exit('REQUIRED STRING %r NOT PRESENT in %s' % (s, path))
    for s in a.forbid:
        if s.encode() in data:
            sys.exit('FORBIDDEN STRING %r STILL PRESENT in %s' % (s, path))
    print('engine', name, os.path.getsize(path), 'bytes -- checks ok')

os.makedirs(os.path.dirname(a.out) or '.', exist_ok=True)
n = 0
with zipfile.ZipFile(a.apk, 'r') as zin, \
     zipfile.ZipFile(a.out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        name = item.filename
        if name.startswith('META-INF/'):
            continue                      # drop old signature, apksigner re-signs
        if name in repl:
            data = open(repl[name], 'rb').read()
            zi = zipfile.ZipInfo(name, date_time=item.date_time)
            zi.external_attr = item.external_attr
            zi.compress_type = zipfile.ZIP_STORED   # extractNativeLibs=false
            zout.writestr(zi, data)
            n += 1
            print('REPLACED', name, len(data), 'bytes (STORED)')
        elif name.endswith('.so'):
            data = zin.read(name)
            zi = zipfile.ZipInfo(name, date_time=item.date_time)
            zi.external_attr = item.external_attr
            zi.compress_type = zipfile.ZIP_STORED
            zout.writestr(zi, data)
        else:
            zout.writestr(item, zin.read(name))
print('replaced entries:', n)
print('wrote', a.out, os.path.getsize(a.out), 'bytes')
