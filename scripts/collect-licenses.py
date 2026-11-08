#!/usr/bin/env python3
"""Collect notices and corresponding module sources from the actual native AAR."""
import json
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
core = root / 'native/husi/libcore'
out = root / 'app/src/main/assets/licenses/go'
sources = root / 'dependency-sources'
out.mkdir(parents=True, exist_ok=True)
sources.mkdir(exist_ok=True)
# Build information survives -s/-w. Do not infer shipped dependencies from the
# much larger lazy module graph (which also contains tools and test dependencies).
required = set()
with zipfile.ZipFile(core / 'libcore.aar') as aar, tempfile.TemporaryDirectory() as tmp:
    libraries = [n for n in aar.namelist() if n.endswith('/libgojni.so')]
    if not libraries:
        raise SystemExit('No real libgojni.so in AAR')
    for index, name in enumerate(libraries):
        binary = pathlib.Path(tmp) / f'core-{index}.so'
        binary.write_bytes(aar.read(name))
        info = subprocess.check_output(['go', 'version', '-m', str(binary)], text=True)
        previous = None
        for line in info.splitlines():
            fields = line.strip().split('\t')
            if fields[0] == 'dep':
                previous = (fields[1], fields[2])
                required.add(previous)
            elif fields[0] == '=>' and previous:
                required.discard(previous)
                if previous[0] == 'github.com/xchacha20-poly1305/husi/libcore/v2':
                    # The local derivative is shipped in KVN-corresponding-source.
                    continue
                if pathlib.Path(fields[1]).is_absolute():
                    raise SystemExit('Unexpected local dependency replacement: ' + previous[0])
                required.add((fields[1], fields[2]))
if not required:
    raise SystemExit('AAR has no dependency build information')
modules = []
missing = []
for module_path, version in sorted(required):
    if version in ('(devel)', ''):
        raise SystemExit(f'Unpinned dependency: {module_path}')
    record = json.loads(subprocess.check_output(
        ['go', 'mod', 'download', '-json', module_path + '@' + version], cwd=core, text=True))
    if record.get('Error'):
        raise SystemExit(record['Error'])
    base = pathlib.Path(record['Dir'])
    target = out / module_path / version
    found = []
    for file in base.iterdir():
        if file.is_file() and file.name.lower().startswith(('license', 'copying', 'notice', 'authors', 'credits')):
            target.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, target / file.name)
            found.append(file.name)
    if not found:
        missing.append(module_path)
    # Preserve the exact distributed module archive, including source headers.
    archive_name = module_path.replace('/', '__') + '@' + version + '.zip'
    shutil.copyfile(record['Zip'], sources / archive_name)
    modules.append({'Path': module_path, 'Version': version, 'Sum': record.get('Sum'),
                    'notices': found, 'archive': archive_name})
(out / 'modules.json').write_text(json.dumps(modules, indent=2) + '\n')
(sources / 'modules.json').write_text(json.dumps(modules, indent=2) + '\n')
if missing:
    raise SystemExit('Missing notices in shipped modules; review before distributing: ' + ', '.join(missing))
print(f'Collected notices and source archives for {len(modules)} shipped modules')
