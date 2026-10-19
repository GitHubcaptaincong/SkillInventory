"""Isolated authenticated Nginx smoke; secrets remain in process memory, never printed."""
import argparse, http.cookiejar, json, pathlib, subprocess, urllib.request, urllib.error, uuid
p=argparse.ArgumentParser()
p.add_argument('--base-url', default='http://127.0.0.1:18089')
p.add_argument('--env-file', default='.test-output/auth-smoke.env')
p.add_argument('--project', default='skillinventory-auth-smoke')
p.add_argument('--restart', action='store_true')
a=p.parse_args()
env=dict(line.split('=',1) for line in pathlib.Path(a.env_file).read_text().splitlines() if line and not line.startswith('#'))
jar=http.cookiejar.CookieJar(); browser=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf=None; key=None

def call(path, method='GET', body=None, expected=200, headers=None, cookies=True):
    h={'Content-Type':'application/json',**(headers or {})}
    if method!='GET' and csrf: h['X-CSRF-TOKEN']=csrf
    if path.startswith('/api/v1/') and key and 'X-API-Key' not in h: h['X-API-Key']=key
    req=urllib.request.Request(a.base_url+path, data=None if body is None else json.dumps(body).encode(),method=method,headers=h)
    try: res=(browser if cookies else urllib.request.build_opener()).open(req,timeout=30)
    except urllib.error.HTTPError as error: res=error
    with res:
        raw=res.read()
        assert res.status==expected, (method,path,res.status,expected)  # never print sensitive response body
        parsed=json.loads(raw) if raw and 'application/json' in res.headers.get('Content-Type','') else raw.decode()
        if res.status>=400: assert res.headers.get('Cache-Control')=='no-store'
        return parsed,dict(res.headers)

def login():
    global csrf
    csrf=call('/api/auth/csrf')[0]['token']
    call('/api/auth/login','POST',{'username':env['ADMIN_USERNAME'],'password':env['ADMIN_INITIAL_PASSWORD']})
    csrf=call('/api/auth/csrf')[0]['token']
    call('/api/auth/me')

def mutate(s,op):return call('/api/admin/skills/'+s['id']+'/'+op,'POST',{'lockVersion':s['lockVersion']})[0]

call('/api/admin/skills',expected=401);call('/api/v1/skills',expected=401)
csrf=call('/api/auth/csrf')[0]['token']
call('/api/auth/login','POST',{'username':env['ADMIN_USERNAME'],'password':'x'*100},401)
login()
created=call('/api/admin/api-keys','POST',{'name':'isolated smoke'},201)[0];key=created['key']
assert key not in json.dumps(call('/api/admin/api-keys')[0])
name='auth-smoke-'+uuid.uuid4().hex[:12]
s=call('/api/admin/skills','POST',{'name':name,'description':'snapshot','content':'# original'},201)[0]
root='/api/v1/skills/'+name
call(root,expected=404,cookies=False)
s=mutate(s,'publish')
for path in ['/api/v1/skills',root,root+'/SKILL.md']:
    _,h=call(path,cookies=False)
    assert h.get('Cache-Control')=='private, no-cache'
    tag=h.get('ETag') or h.get('Etag')
    call(path,expected=304,headers={'If-None-Match':tag},cookies=False)
    call(path,expected=401,headers={'If-None-Match':tag,'X-API-Key':'wrong'},cookies=False)
s=call('/api/admin/skills/'+s['id'],'PUT',{'description':'unpublished','content':'# changed','lockVersion':s['lockVersion']})[0]
assert call(root+'/SKILL.md',cookies=False)[0].endswith('# original')
call('/api/admin/skills','POST',{},403,{'Origin':'https://evil.invalid'})
if a.restart:
    # Same process retains the key in RAM across container recreation; no plaintext key file.
    command=['docker','compose','--env-file',a.env_file,'-p',a.project]
    subprocess.run(command+['down'],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(command+['up','-d','--wait','--wait-timeout','240'],check=True,stdout=subprocess.DEVNULL)
    call('/api/auth/me')  # persistent server session
    login()  # original credentials still valid
    assert call('/api/admin/skills/'+s['id'])[0]==s
    assert call(root+'/SKILL.md',cookies=False)[0].endswith('# original')
s=mutate(s,'unpublish');call(root,expected=404,cookies=False)
s=mutate(s,'publish')
call('/api/admin/api-keys/'+created['metadata']['id']+'/revoke','POST',{})
call('/api/admin/api-keys/'+created['metadata']['id']+'/revoke','POST',{})
call(root,expected=401,headers={'If-None-Match':'*'},cookies=False)
call('/api/auth/logout','POST',{},204)
call('/api/auth/me',expected=401)
command=['docker','compose','--env-file',a.env_file,'-p',a.project]
logs=subprocess.run(command+['logs','--no-color'],capture_output=True,check=True).stdout
for value in [key,env['ADMIN_INITIAL_PASSWORD'],env['DB_PASSWORD'],env['MYSQL_ROOT_PASSWORD']]:
    assert value.encode() not in logs, 'Credential unexpectedly present in service logs'
dump=subprocess.run(command+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u"$MYSQL_USER" --no-tablespaces "$MYSQL_DATABASE"'],capture_output=True,check=True).stdout
assert key.encode() not in dump, 'Plaintext API Key unexpectedly persisted'
assert env['ADMIN_INITIAL_PASSWORD'].encode() not in dump, 'Plaintext admin password unexpectedly persisted'
print('PASS: log/database plaintext credential absence')
print('PASS: Nginx login, CSRF, lifecycle, snapshot isolation, API Key/ETag, revoke, logout'+(', down/up persistence including session' if a.restart else ''))
