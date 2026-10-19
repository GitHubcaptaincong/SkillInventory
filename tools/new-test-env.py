"""Creates a disposable local test environment; does not replace an existing file."""
import pathlib
import secrets
p = pathlib.Path('.test-output/smoke.env')
p.parent.mkdir(exist_ok=True)
with p.open('x', encoding='utf-8', newline='\n') as f:
    f.write('FRONTEND_BIND=127.0.0.1\nFRONTEND_PORT=18088\nDB_NAME=skill_smoke\nDB_USER=skill_smoke\n')
    f.write('DB_PASSWORD=' + secrets.token_hex(24) + '\nMYSQL_ROOT_PASSWORD=' + secrets.token_hex(24) + '\n')
    f.write('ALLOWED_ORIGINS=http://127.0.0.1:18088\n')
print('Created .test-output/smoke.env with random test passwords; values not printed.')
