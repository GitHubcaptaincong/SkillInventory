"""Creates a disposable local test environment; does not replace an existing file."""
import pathlib
import secrets
import argparse
a=argparse.ArgumentParser();a.add_argument('--output',default='.test-output/auth-smoke.env');a.add_argument('--port',default='18089');args=a.parse_args()
p = pathlib.Path(args.output)
p.parent.mkdir(exist_ok=True)
with p.open('x', encoding='utf-8', newline='\n') as f:
    f.write('FRONTEND_BIND=127.0.0.1\nFRONTEND_PORT=' + args.port + '\nDB_NAME=skill_smoke\nDB_USER=skill_smoke\n')
    f.write('DB_PASSWORD=' + secrets.token_hex(24) + '\nMYSQL_ROOT_PASSWORD=' + secrets.token_hex(24) + '\n')
    f.write('ALLOWED_ORIGINS=http://127.0.0.1:' + args.port + '\n')
    f.write('ADMIN_USERNAME=smoke-admin\nADMIN_INITIAL_PASSWORD=' + secrets.token_hex(24) + '\nSESSION_COOKIE_SECURE=false\n')
print('Created isolated test env with random test passwords; values not printed.')
