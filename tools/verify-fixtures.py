#!/usr/bin/env python3
"""Verify the bundled observation bytes against the extraction provenance."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / "provenance.json").read_text())
for fixture in manifest["fixtures"]:
    path = root / fixture["path"]
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != fixture["sha256"]:
        raise SystemExit(f"Fixture differs from its recorded observation: {fixture['path']}")
print(f"Verified {len(manifest['fixtures'])} observation fixtures.")
