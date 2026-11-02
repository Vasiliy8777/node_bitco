"""Project pinned Core v32.1 consensus data without modifying expected results."""
import argparse
import base64
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'consensus/src/test/resources/bitcoin-core-v32'
VERSION = 'v32.1'

def b64(value):
    return base64.b64encode(str(value).encode('utf-8')).decode('ascii')

def write_rows(name, rows):
    data = ('\n'.join(rows) + '\n').encode('utf-8')
    (DEST / name).write_bytes(data)
    print(f'{name}: {len(rows)} rows SHA256 {hashlib.sha256(data).hexdigest()}')

parser = argparse.ArgumentParser()
parser.add_argument('--source-directory', type=Path, help='Use already downloaded core32-*.json files')
args = parser.parse_args()
DEST.mkdir(parents=True, exist_ok=True)
corpora = {}
manifest = []
for name in ('script_tests.json', 'tx_valid.json', 'tx_invalid.json'):
    if args.source_directory:
        data = (args.source_directory / ('core32-' + name)).read_bytes()
    else:
        url = f'https://raw.githubusercontent.com/bitcoin/bitcoin/{VERSION}/src/test/data/{name}'
        data = urllib.request.urlopen(url, timeout=60).read()
    (DEST / name).write_bytes(data)
    manifest.append(f'{hashlib.sha256(data).hexdigest()}  {name}')
    corpora[name] = json.loads(data, parse_float=Decimal)

rows = []
for row in corpora['script_tests.json']:
    if len(row) < 4:
        continue
    witness, amount = [], 0
    if isinstance(row[0], list):
        witness = row[0][:-1]
        amount = int(Decimal(row[0][-1]) * 100_000_000)
        row = row[1:]
    fields = row[:4] + [amount, len(witness), ';'.join(witness)]
    rows.append('|'.join(b64(field) for field in fields))
write_rows('script-all.txt', rows)

for name in ('tx_valid', 'tx_invalid'):
    rows = []
    for row in corpora[name + '.json']:
        if len(row) != 3 or not isinstance(row[0], list):
            continue
        coins = []
        for coin in row[0]:
            txid, index, script = coin[:3]
            amount = coin[3] if len(coin) > 3 else 0
            coins.append(f'{txid},{index},{amount},{b64(script)}')
        rows.append('|'.join([row[1], row[2]] + coins))
    write_rows(name + '.txt', rows)
(DEST / 'SHA256SUMS').write_text('\n'.join(manifest) + '\n', encoding='utf-8')
