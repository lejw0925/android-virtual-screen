#!/usr/bin/env python3
import hashlib
import json
import pathlib
import subprocess
root = pathlib.Path(__file__).resolve().parent.parent
manifest = {
    'ndk': '28.2.13676358', 'api': 33, 'go': subprocess.check_output(['go', 'version'], text=True).strip(),
    'patches': [{'file': 'scripts/patch-native.py', 'purpose': 'Honor cloudflared login --origincert and use the Android system DNS bridge for Go SRV/TXT/A/AAAA', 'sha256': hashlib.sha256((root / 'scripts/patch-native.py').read_bytes()).hexdigest()}],
    'sources': [
        {'name': 'frpc', 'version': '0.65.0', 'url': 'https://github.com/fatedier/frp/tree/v0.65.0', 'archiveSha256': 'bbec0d1855e66c96e3a79ff97b8c74d9b1b45ec560aa7132550254d48321f7de', 'license': 'Apache-2.0'},
        {'name': 'cloudflared', 'version': '2025.8.1', 'url': 'https://github.com/cloudflare/cloudflared/tree/2025.8.1', 'archiveSha256': '803d3e162dc91da6b3b4f7961f57c113fa818d45517758511f72d254d68a46ba', 'license': 'Apache-2.0'},
    ],
    'binaries': [
        {'path': str(p.relative_to(root / 'app/src/main')), 'size': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
        for p in sorted((root / 'app/src/main/jniLibs').glob('*/*.so'))
    ],
}
(root / 'app/src/main/assets/native-build.json').write_text(json.dumps(manifest, indent=2) + '\n')
