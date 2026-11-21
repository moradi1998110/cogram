#!/usr/bin/env python3
"""Publishes Phogram's weather tiles on the organization's GitLab, where the phones already find their updates.

The tiles of a run go to the generic package phogram-weather/<run>, then index.json to phogram-weather/latest, so a
phone never reads an index whose tiles are not there yet. Runs older than the previous one are deleted, and the
latest package keeps only its newest index, so the project does not grow with every run.

Usage: publish-weather.py <out dir>
Environment: GITLAB_API (…/projects/<id>/packages/generic), GITLAB_PROJECT_API (…/projects/<id>), GITLAB_TOKEN.
"""

import json
import os
import sys
import urllib.request

PACKAGE = 'phogram-weather'


def request(method, url, token, data=None):
    req = urllib.request.Request(url, data=data, method=method, headers={'PRIVATE-TOKEN': token})
    with urllib.request.urlopen(req, timeout=120) as answer:
        body = answer.read()
        return json.loads(body) if body[:1] in (b'[', b'{') else None


def upload(api, token, version, path):
    with open(path, 'rb') as f:
        request('PUT', f'{api}/{PACKAGE}/{version}/{os.path.basename(path)}', token, f.read())


def clean(project_api, token, keep):
    """Deletes the runs not kept, and every index but the newest in the latest package. A failure only warns."""
    try:
        packages = request('GET', f'{project_api}/packages?package_name={PACKAGE}&package_type=generic&per_page=100', token)
        for package in packages or []:
            if package.get('name') != PACKAGE:
                continue
            version = package.get('version')
            if version == 'latest':
                files = request('GET', f'{project_api}/packages/{package["id"]}/package_files?per_page=100', token) or []
                files.sort(key=lambda f: f['id'])
                for old in files[:-1]:
                    request('DELETE', f'{project_api}/packages/{package["id"]}/package_files/{old["id"]}', token)
            elif version not in keep:
                request('DELETE', f'{project_api}/packages/{package["id"]}', token)
                print(f'deleted run {version}')
    except Exception as e:
        print(f'cleaning up failed, the old runs stay: {e}', file=sys.stderr)


def main():
    out = sys.argv[1]
    api = os.environ['GITLAB_API'].rstrip('/')
    project_api = os.environ['GITLAB_PROJECT_API'].rstrip('/')
    token = os.environ['GITLAB_TOKEN']
    with open(os.path.join(out, 'index.json')) as f:
        index = json.load(f)
    version = index['version']
    for name in sorted(os.listdir(out)):
        if name.startswith('w_') and name.endswith('.json.gz'):
            upload(api, token, version, os.path.join(out, name))
    upload(api, token, 'latest', os.path.join(out, 'index.json'))
    print(f'published run {version}: {len(index["tiles"])} tiles')
    # The run before stays a while, for phones that read the previous index a moment ago.
    versions = sorted(p.get('version') for p in (request(
        'GET', f'{project_api}/packages?package_name={PACKAGE}&package_type=generic&per_page=100', token) or [])
        if p.get('name') == PACKAGE and p.get('version') != 'latest')
    keep = set(versions[-2:]) | {version}
    clean(project_api, token, keep)


if __name__ == '__main__':
    main()
