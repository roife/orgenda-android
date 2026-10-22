#!/usr/bin/env python3
"""Read-only check of the exact iOS working tree captured for this port."""
import hashlib
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parent.parent
baseline = json.loads((root / 'docs/ios-baseline.json').read_text())
source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(baseline['source'])
changed = []
for name, expected in baseline['sha256'].items():
    path = source / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        changed.append(name)
if changed:
    print('\n'.join(changed))
    sys.exit(1)
print(f"Unchanged: {len(baseline['sha256'])} source files, including the captured dirty working tree.")
