#!/usr/bin/env python3
"""Phogram's weather tiles: ECMWF's open forecast over Iraq, cut into one-degree tiles the phones fetch per trip.

ECMWF's open data (CC BY 4.0, (c) ECMWF) is global, in GRIB2, far too large for a phone. This script, run on a
schedule by .github/workflows/weather.yml, takes only the surface fields a driver needs from the latest IFS
forecast, keeps Iraq's box, and writes:

  out/w_<lat>_<lon>.json.gz   one tile per degree square, its south-west corner in the name, with its edges, so the
                              phone interpolates inside a tile without its neighbours;
  out/index.json              the run, the valid times, and each tile's SHA-256, which the phone checks.

A phone fetches index.json, then only the tiles its route crosses, and deletes them when the trip ends. It never
sends its route anywhere: a tile is a square that every driver crossing it asks for.

Every value is an integer (JSON stays small and simple to read):
  t  temperature at 2 m, degC              d  temperature minus dew point, degC (dry air is large, fog is near 0)
  w  mean wind at 10 m, km/h               g  strongest gust at 10 m in the last 3 hours, km/h
  r  precipitation in the last 3 hours, tenths of a millimetre
  p  precipitation type, WMO code table 4.201 (0 none, 1 rain, 3 freezing rain, 5 snow, 6 wet snow, 7 mixed,
     8 ice pellets, 12 freezing drizzle)
  c  cloud cover, %                        k  most-unstable CAPE (thunderstorm energy), tens of J/kg
  f  fog likelihood, 0-100, from a near-saturated, calm surface layer (the open data has no visibility field)

Usage: build-weather.py <grib file> <run, YYYY-MM-DDTHH> <resolution in degrees> <out dir>
"""

import gzip
import hashlib
import json
import math
import os
import sys
from datetime import datetime, timedelta, timezone

import eccodes
import numpy as np

# Iraq with a margin: 29-38 N, 38-49 E; tiles of one degree, named by their south-west corner.
SOUTH, NORTH, WEST, EAST = 29, 38, 38, 49
PARAMS = ['2t', '2d', '10u', '10v', '10fg3', 'tp', 'ptype', 'tcc', 'mucape']
# ecCodes' names where they differ from the open data's request names.
ALIASES = {'fg310': '10fg3'}
# Fields a run may lack at some steps; the tile then has no gust beyond the wind, no storm energy, and so on.
OPTIONAL = {'10fg3', 'ptype', 'tcc', 'mucape'}


def read_fields(path, res):
    """The fields over the box, by (short name, step): arrays from south to north, west to east."""
    rows = int(round((NORTH - SOUTH) / res)) + 1
    cols = int(round((EAST - WEST) / res)) + 1
    fields = {}
    with open(path, 'rb') as f:
        while True:
            gid = eccodes.codes_grib_new_from_file(f)
            if gid is None:
                break
            try:
                name = eccodes.codes_get(gid, 'shortName')
                name = ALIASES.get(name, name)
                step = int(eccodes.codes_get(gid, 'endStep'))
                ni = eccodes.codes_get(gid, 'Ni')
                nj = eccodes.codes_get(gid, 'Nj')
                lat1 = eccodes.codes_get(gid, 'latitudeOfFirstGridPointInDegrees')
                lon1 = eccodes.codes_get(gid, 'longitudeOfFirstGridPointInDegrees')
                dlat = eccodes.codes_get(gid, 'jDirectionIncrementInDegrees')
                dlon = eccodes.codes_get(gid, 'iDirectionIncrementInDegrees')
                south_first = eccodes.codes_get(gid, 'jScansPositively') == 1
                if abs(dlat - res) > 1e-6 or abs(dlon - res) > 1e-6:
                    raise SystemExit(f'{name} step {step}: grid {dlat}x{dlon}, expected {res}')
                values = eccodes.codes_get_values(gid).reshape(nj, ni)
            finally:
                eccodes.codes_release(gid)
            lats = SOUTH + np.arange(rows) * res
            lons = WEST + np.arange(cols) * res
            j = np.rint(((lats - lat1) if south_first else (lat1 - lats)) / dlat).astype(int)
            i = np.rint(((lons - lon1) % 360) / dlon).astype(int) % ni
            if j.min() < 0 or j.max() >= nj:
                raise SystemExit(f'{name}: the grid does not cover Iraq')
            fields[(name, step)] = values[np.ix_(j, i)].astype(np.float64)
    print('fields:', ', '.join(sorted({name for name, _ in fields})))
    return fields, rows, cols


def derive(fields, steps):
    """The driver's values at each step, as integer arrays."""
    out = {key: [] for key in 'tdwgrpckf'}
    for s in steps:
        def field(name):
            value = fields.get((name, s))
            if value is None:
                if name in OPTIONAL:
                    return np.zeros_like(fields[('2t', s)])
                raise SystemExit(f'missing {name} at step {s}')
            return value
        t = field('2t') - 273.15
        dew = field('2d') - 273.15
        spread = np.maximum(t - dew, 0)
        wind = np.hypot(field('10u'), field('10v')) * 3.6
        gust = np.maximum(field('10fg3') * 3.6, wind)
        # Total precipitation is accumulated from the run's start; the last three hours are the difference.
        before = fields.get(('tp', s - 3))
        rain = (field('tp') - (before if before is not None else 0)) * 1000
        rain = np.maximum(rain, 0)
        # Fog: air within a degree or two of its dew point, under a light wind. Without a visibility field this is
        # an estimate, shown as a likelihood.
        fog = np.clip((2.5 - spread) / 2.5, 0, 1) * np.clip((18 - wind) / 12, 0, 1) * 100
        fog[rain > 0.5] = 0
        out['t'].append(np.rint(t))
        out['d'].append(np.rint(spread))
        out['w'].append(np.rint(wind))
        out['g'].append(np.rint(gust))
        out['r'].append(np.rint(rain * 10))
        out['p'].append(np.rint(field('ptype')))
        out['c'].append(np.rint(np.clip(field('tcc'), 0, 1) * 100))
        out['k'].append(np.rint(np.maximum(field('mucape'), 0) / 10))
        out['f'].append(np.rint(fog))
    return out


def main():
    grib, run_text, res_text, out_dir = sys.argv[1:5]
    res = float(res_text)
    run = datetime.strptime(run_text, '%Y-%m-%dT%H').replace(tzinfo=timezone.utc)
    os.makedirs(out_dir, exist_ok=True)
    fields, rows, cols = read_fields(grib, res)
    steps = sorted({step for (name, step) in fields if name == '2t' and step >= 3})
    if not steps:
        raise SystemExit('no forecast steps in the file')
    values = derive(fields, steps)
    times = [int((run + timedelta(hours=s)).timestamp()) for s in steps]
    per = int(round(1 / res))
    tiles = {}
    for lat in range(SOUTH, NORTH):
        for lon in range(WEST, EAST):
            r0 = int(round((lat - SOUTH) / res))
            c0 = int(round((lon - WEST) / res))
            tile = {
                'v': 1,
                'run': run.strftime('%Y-%m-%dT%H:00:00Z'),
                'lat': lat, 'lon': lon, 'res': res, 'n': per + 1,
                'times': times,
                # Each field: one list per valid time, the points row by row from south to north, west to east.
                'f': {key: [[int(v) for v in grid[r0:r0 + per + 1, c0:c0 + per + 1].ravel()] for grid in series]
                      for key, series in values.items()},
            }
            data = gzip.compress(json.dumps(tile, separators=(',', ':')).encode(), compresslevel=9, mtime=0)
            name = f'w_{lat}_{lon}.json.gz'
            with open(os.path.join(out_dir, name), 'wb') as f:
                f.write(data)
            tiles[f'{lat}_{lon}'] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
    version = run.strftime('%Y%m%d%H')
    index = {
        'v': 1,
        'run': run.strftime('%Y-%m-%dT%H:00:00Z'),
        'version': version,
        'res': res,
        'box': [SOUTH, WEST, NORTH, EAST],
        'times': times,
        'tiles': tiles,
        'source': 'ECMWF IFS open data, CC BY 4.0',
    }
    with open(os.path.join(out_dir, 'index.json'), 'w') as f:
        json.dump(index, f, separators=(',', ':'))
    sizes = [t['bytes'] for t in tiles.values()]
    print(f'run {version}: {len(tiles)} tiles, {len(times)} times, {min(sizes)}-{max(sizes)} bytes each, '
          f'{sum(sizes) / 1024:.0f} KiB in all')


if __name__ == '__main__':
    main()
