"""Public-entry API smoke; never connects directly to MySQL. No third-party packages."""
import argparse
import json
import pathlib
import urllib.request
import urllib.error
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://127.0.0.1:18088')
parser.add_argument('--phase', choices=['before-restart', 'after-restart'], required=True)
parser.add_argument('--state', default='.test-output/smoke-state.json')
args = parser.parse_args()
base = args.base_url.rstrip('/')
state_path = pathlib.Path(args.state)

def call(path, method='GET', body=None, expected=200, headers=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
        headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        res = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        res = error
    with res:
        raw = res.read()
        assert res.status == expected, (method, path, res.status, raw[:250])
        parsed = json.loads(raw) if raw and 'application/json' in res.headers.get('Content-Type', '') else raw.decode()
        return parsed, dict(res.headers)

def mutate(skill, operation):
    return call('/api/admin/skills/' + skill['id'] + '/' + operation, 'POST', {'lockVersion': skill['lockVersion']})[0]

if args.phase == 'before-restart':
    assert '<div id="app">' in call('/')[0]
    assert '<div id="app">' in call('/skills/new')[0]  # history fallback
    call('/api/not-an-api', expected=404)  # API errors do not become HTML
    name = 'smoke-' + uuid.uuid4().hex[:12]
    s, _ = call('/api/admin/skills', 'POST', {'name': name, 'description': '冒烟: 旧描述', 'content': '  # 旧正文\n'}, 201)
    root = '/api/v1/skills/' + name
    call(root, expected=404)
    s = mutate(s, 'publish')
    raw, headers = call(root + '/SKILL.md')
    etag = headers.get('ETag') or headers.get('Etag')
    assert etag and '# 旧正文' in raw
    call(root + '/SKILL.md', expected=304, headers={'If-None-Match': etag})
    s, _ = call('/api/admin/skills/' + s['id'], 'PUT', {'description': '未发布的新描述', 'content': '# 新正文', 'lockVersion': s['lockVersion']})
    assert call(root + '/SKILL.md')[0] == raw
    assert call(root)[0]['description'] == '冒烟: 旧描述'
    call('/api/admin/skills/' + s['id'] + '?lockVersion=' + str(s['lockVersion']), 'DELETE', expected=409)
    s = mutate(s, 'publish')
    assert '# 新正文' in call(root + '/SKILL.md')[0]
    s = mutate(s, 'unpublish')
    call(root + '/SKILL.md', expected=404, headers={'If-None-Match': etag})
    call(root, expected=404)
    assert not any(item['name'] == name for item in call('/api/v1/skills')[0]['items'])
    call('/api/admin/skills/' + s['id'] + '?lockVersion=' + str(s['lockVersion']), 'DELETE', expected=204)
    s, _ = call('/api/admin/skills', 'POST', {'name': name, 'description': '重启持久化验证', 'content': '# 持久化正文'}, 201)
    s = mutate(s, 'publish')
    call('/api/admin/skills', 'POST', {}, 403, {'Origin': 'https://evil.invalid'})
    call('/api/admin/skills', 'POST', {'name': 'too-large', 'description': 'x', 'content': 'x' * (2 * 1024 * 1024)}, 413)
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps(s, ensure_ascii=False), encoding='utf-8')
    print('PASS: public-entry lifecycle, snapshot isolation, ETag, origins, 413, history/API routing. Persistence record retained for restart.')
else:
    s = json.loads(state_path.read_text(encoding='utf-8'))
    actual = call('/api/admin/skills/' + s['id'])[0]
    assert actual == s, (actual, s)
    assert '# 持久化正文' in call('/api/v1/skills/' + s['name'] + '/SKILL.md')[0]
    s = mutate(actual, 'unpublish')
    call('/api/admin/skills/' + s['id'] + '?lockVersion=' + str(s['lockVersion']), 'DELETE', expected=204)
    print('PASS: persisted draft and published snapshot survived container recreation; smoke record removed.')
