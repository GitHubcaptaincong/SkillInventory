"""Read-only external Skill API checks. Python 3.10+, standard library only."""
import argparse
import getpass
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the API key to a redirect target.
        return None


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8088',
                        help='Server origin, e.g. http://192.168.1.20:8088')
    parser.add_argument('--name', help='Published Skill name; otherwise use first list item')
    parser.add_argument('--timeout', type=float, default=15, help='Per-request timeout in seconds')
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    url = urllib.parse.urlsplit(base)
    require(url.scheme in ('http', 'https') and url.hostname and not url.username
            and not url.password and not url.query and not url.fragment
            and url.path in ('', '/'), '--base-url must be an HTTP(S) server origin')
    require(args.timeout > 0, '--timeout must be positive')
    require(args.name is None or (len(args.name) <= 64 and
            re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', args.name)), 'Invalid Skill name')
    key = os.environ.get('SKILL_API_KEY') or getpass.getpass('Skill API Key: ')
    require(bool(key.strip()), 'API Key is required')
    opener = urllib.request.build_opener(NoRedirect())
    passed = 0

    def call(path, expected=200, credential=key, etag=None):
        nonlocal passed
        headers = {}
        if credential is not None:
            headers['X-API-Key'] = credential
        if etag:
            headers['If-None-Match'] = etag
        req = urllib.request.Request(base + path, headers=headers, method='GET')
        try:
            response = opener.open(req, timeout=args.timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read()
            require(response.status == expected,
                    f'GET {path}: expected {expected}, received {response.status}')
            if expected >= 400:
                require(response.headers.get('Cache-Control') == 'no-store',
                        f'{path}: error response must use no-store')
            passed += 1
            print(f'PASS {response.status} GET {path}')
            return body, response.headers

    def cached(path, content_type):
        body, headers = call(path)
        require(content_type in headers.get('Content-Type', ''), f'{path}: wrong Content-Type')
        require(headers.get('Cache-Control') == 'private, no-cache', f'{path}: wrong Cache-Control')
        require('x-api-key' in headers.get('Vary', '').lower(), f'{path}: missing Vary X-API-Key')
        tag = headers.get('ETag')
        require(bool(tag), f'{path}: missing ETag')
        empty, _ = call(path, 304, etag=tag)
        require(not empty, f'{path}: 304 must have no body')
        call(path, 401, credential='invalid-test-key', etag=tag)
        return body

    root = '/api/v1/skills'
    call(root, 401, credential=None)
    call(root, 401, credential='invalid-test-key')
    listing = json.loads(cached(root + '?page=1&pageSize=20', 'application/json'))
    require(isinstance(listing.get('items'), list) and listing.get('page') == 1
            and listing.get('pageSize') == 20 and isinstance(listing.get('total'), int),
            'Invalid list pagination shape')
    for item in listing['items']:
        require(not {'content', 'skillMd', 'publishedSnapshot', 'lockVersion'} & item.keys(),
                'List exposes body or draft fields')
    call(root + '?page=0', 400)
    call(root + '?pageSize=101', 400)
    name = args.name or (listing['items'][0]['name'] if listing['items'] else None)
    require(name is not None, 'No published Skills: publish one or specify --name; detail/raw checks incomplete')
    require(len(name) <= 64 and re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', name),
            'Invalid Skill name returned by list')
    path = root + '/' + name
    detail = json.loads(cached(path, 'application/json'))
    require(detail.get('name') == name and isinstance(detail.get('skillMd'), str), 'Invalid detail')
    require(not {'content', 'publishedSnapshot', 'lockVersion'} & detail.keys(), 'Detail exposes draft fields')
    require(detail.get('skillMdPath') == path + '/SKILL.md', 'Unexpected skillMdPath')
    raw = cached(path + '/SKILL.md', 'text/plain').decode('utf-8')
    require(raw == detail['skillMd'], 'Raw SKILL.md differs from JSON detail (possible concurrent publish; retry)')
    require(raw.startswith('---\n'), 'SKILL.md frontmatter missing')
    print(f'PASS: {passed} HTTP checks plus response assertions; no data changed.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, urllib.error.URLError, EOFError) as error:
        # Do not print response bodies, credentials, or exception URLs.
        if isinstance(error, ValueError):
            print(f'FAIL: {error}', file=sys.stderr)
        else:
            print(f'FAIL: {type(error).__name__}; check server, network and API contract.', file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
