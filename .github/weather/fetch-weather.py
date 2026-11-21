#!/usr/bin/env python3
"""Downloads the surface fields of ECMWF's latest IFS forecast that Phogram's weather tiles need.

Only those fields are fetched, by byte range, through ECMWF's own client (ecmwf-opendata, Apache-2.0); the rest of
the global files is never downloaded. The run is the newest one that already has every step asked for.

Usage: fetch-weather.py <target grib file> <published index URL, or empty>
Prints the run as YYYY-MM-DDTHH, or SKIP when that run is already published.
Environment: WEATHER_RESOL (0p1 or 0p25), WEATHER_MAX_STEP (hours), WEATHER_SOURCES (comma list), WEATHER_FORCE.
"""

import json
import os
import signal
import sys
import urllib.request

from ecmwf.opendata import Client

PARAMS = ['2t', '2d', '10u', '10v', '10fg3', 'tp', 'ptype', 'tcc', 'mucape']


def published_version(url):
    if not url:
        return None
    try:
        with urllib.request.urlopen(url, timeout=30) as answer:
            return json.load(answer).get('version')
    except Exception as e:
        print(f'no published index yet ({e})', file=sys.stderr)
        return None


def main():
    target, index_url = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else ''
    resol = os.environ.get('WEATHER_RESOL', '0p1')
    max_step = int(os.environ.get('WEATHER_MAX_STEP', '48'))
    steps = list(range(3, max_step + 1, 3))
    errors = []
    # ECMWF's own portal first: the cloud mirrors may not carry the 0.1 degree files and answer 503, which the
    # client retries for hours. Each source gets a time limit, then the next one is tried.
    limit = int(os.environ.get('WEATHER_SOURCE_MINUTES', '15')) * 60
    def too_slow(*_):
        raise TimeoutError(f'no answer in {limit} s')

    signal.signal(signal.SIGALRM, too_slow)
    for source in os.environ.get('WEATHER_SOURCES', 'ecmwf,aws').split(','):
        signal.alarm(limit)
        try:
            client = Client(source=source.strip(), model='ifs', resol=resol)
            run = client.latest(type='fc', step=max_step, param='2t', levtype='sfc')
            version = run.strftime('%Y%m%d%H')
            print(f'{source}: latest run {version}', file=sys.stderr)
            if version == published_version(index_url) and not os.environ.get('WEATHER_FORCE'):
                print('SKIP')
                return
            client.retrieve(date=run.strftime('%Y-%m-%d'), time=run.hour, type='fc', step=steps, param=PARAMS,
                            levtype='sfc', target=target)
            print(f'{source}: {os.path.getsize(target) / 1e6:.0f} MB', file=sys.stderr)
            print(run.strftime('%Y-%m-%dT%H'))
            return
        except Exception as e:
            errors.append(f'{source}: {e}')
            print(f'{source} failed: {e}', file=sys.stderr)
        finally:
            signal.alarm(0)
    raise SystemExit('every source failed: ' + '; '.join(errors))


if __name__ == '__main__':
    main()
