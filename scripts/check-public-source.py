"""Check the tracked release files without printing credential values."""
from pathlib import Path
import json
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
if (root / '.git').exists():
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    paths = [root / name for name in names if name]
else:
    ignored = {'.git', '.runtime', 'node_modules', 'dist', 'target', 'test-results', 'playwright-report'}
    paths = [p for p in root.rglob('*') if p.is_file() and not ignored.intersection(p.relative_to(root).parts)]

patterns = {
    'provider-key': re.compile(r'\bsk-[A-Za-z0-9_-]{24,}\b'),
    'github-token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b'),
    'cloud-access-key': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'private-key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'jwt': re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b'),
    'personal-windows-path': re.compile(r'(?i)[A-Z]:[/\\]+Users[/\\]+(?!Public\b|Default\b)[^\s/\\]+'),
    'personal-home-path': re.compile(r'/(?:Users|home)/[a-zA-Z][a-zA-Z0-9_-]+/'),
}
forbidden_dirs = {'.runtime', 'node_modules', 'dist', 'target', 'screenshots', 'browser-profile'}
forbidden_suffixes = {'.pem', '.key', '.p12', '.pfx', '.sqlite', '.sqlite3', '.db', '.log', '.zip', '.jar', '.asar', '.exe', '.mp3', '.mp4'}
findings = []
for p in sorted(paths):
    rel = p.relative_to(root)
    if (forbidden_dirs.intersection(rel.parts) or p.suffix.lower() in forbidden_suffixes
            or (p.name.startswith('.env') and p.name != '.env.example')):
        findings.append({'path': rel.as_posix(), 'kind': 'private-or-generated-file'})
    data = p.read_bytes()
    try:
        text = data.decode('utf-8-sig')
    except UnicodeError:
        findings.append({'path': rel.as_posix(), 'kind': 'binary-needs-manual-review'})
        continue
    for kind, pattern in patterns.items():
        for match in pattern.finditer(text):
            findings.append({'path': rel.as_posix(), 'line': text.count('\n', 0, match.start()) + 1, 'kind': kind})
    if p.name == '.env.example':
        for index, line in enumerate(text.splitlines(), 1):
            if line.startswith('#') or '=' not in line:
                continue
            name, value = line.split('=', 1)
            if re.search(r'(?:API_KEY|ACCESS_TOKEN|APP_ID|COOKIE|PASSWORD|TOKEN_FILE)$', name) and value.strip():
                findings.append({'path': rel.as_posix(), 'line': index, 'kind': 'nonempty-credential-example'})

print(json.dumps({'filesChecked': len(paths), 'findings': findings, 'passed': not findings}, ensure_ascii=False, indent=2))
sys.exit(bool(findings))
