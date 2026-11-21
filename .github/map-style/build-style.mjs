// Turns Protomaps' light and dark styles into Phogram's, and writes the landmark icons the app draws.
//
//   node build-style.mjs <protomaps-styles-dir> <maki-icons-dir> <temaki-icons-dir> <out-dir>
//
// Protomaps' styles are muted on purpose and show about 36 kinds of landmarks with a few generic icons. Phogram's:
//  - colors in the manner of today's maps: orange motorways, yellow main roads, blue water, green parks, tinted areas
//    for hospitals, schools and airports, with a dark palette that matches Telegram's dark theme;
//  - Telegram's fonts for the labels (Roboto with Noto Sans Arabic, as "Phogram Regular/Medium/Bold"), dark text on a
//    clear halo, and larger, graded sizes: cities, then neighbourhoods, roads and landmarks;
//  - landmarks Iraq's maps need (mosques and shrines, hospitals, pharmacies, fuel, banks, police, government, hotels,
//    markets, parking...) as round badges colored by category with Maki and Temaki symbols (both CC0), the standard
//    map icon sets; important ones show a zoom earlier, and icons grow a little as the map zooms in;
//  - paint changes fade over 300 ms, on top of MapLibre's own fade of labels as they are placed.
// No dependency: only Node's own modules.

import fs from 'node:fs';
import path from 'node:path';

const [stylesDir, makiDir, temakiDir, outDir] = process.argv.slice(2);
if (!outDir) {
  console.error('usage: node build-style.mjs <protomaps-styles-dir> <maki-icons-dir> <temaki-icons-dir> <out-dir>');
  process.exit(2);
}

// Categories: the badge's color, and the label's color on the light and the dark map. The badges take Telegram's
// own seven colors, those of its profile pictures (red, orange, violet, green, cyan, blue, pink), so the map reads
// as part of Telegram; the few categories past seven take shades between them. Labels are darker shades of the
// badge on the light map and lighter ones on the dark map, for contrast.
const CATEGORIES = {
  health:     { badge: '#E56555', light: '#B8402F', dark: '#FF9A8C' }, // Telegram red
  food:       { badge: '#F28C48', light: '#B25A17', dark: '#FFB37F' }, // Telegram orange
  culture:    { badge: '#8E85EE', light: '#5B51C4', dark: '#B9B2FF' }, // Telegram violet
  leisure:    { badge: '#76C84D', light: '#3E8A1E', dark: '#A3E283' }, // Telegram green
  transport:  { badge: '#5FBED5', light: '#227F96', dark: '#93DCEE' }, // Telegram cyan
  shop:       { badge: '#549CDD', light: '#2565A3', dark: '#8FC0EE' }, // Telegram blue
  lodging:    { badge: '#F2749A', light: '#B83B62', dark: '#FFA3BF' }, // Telegram pink
  religion:   { badge: '#45AE9D', light: '#1F7567', dark: '#83D6C8' }, // between green and cyan
  finance:    { badge: '#3E9DB5', light: '#1C6577', dark: '#86CBDC' }, // deeper cyan
  education:  { badge: '#C08A5B', light: '#7E5530', dark: '#E2B992' }, // warm brown beside orange
  government: { badge: '#7C8C9E', light: '#4A5866', dark: '#B9C4D0' }, // slate
  other:      { badge: '#8E969E', light: '#4E555C', dark: '#C2C8CE' },
};

// Protomaps' "kind" of a landmark (OSM's amenity, shop, tourism... value) -> category and icon names, the first one
// found in Maki, then Temaki. Places of worship are keyed by religion ("place_of_worship:muslim").
const KINDS = {
  restaurant: ['food', 'restaurant'], fast_food: ['food', 'fast-food'], cafe: ['food', 'cafe'], bar: ['food', 'bar'],
  pub: ['food', 'bar'], bakery: ['food', 'bakery'], ice_cream: ['food', 'ice-cream'], food_court: ['food', 'restaurant'],

  supermarket: ['shop', 'grocery'], convenience: ['shop', 'convenience', 'shop'], grocery: ['shop', 'grocery'],
  greengrocer: ['shop', 'grocery'], marketplace: ['shop', 'market', 'shop'], mall: ['shop', 'shop'],
  department_store: ['shop', 'shop'], clothes: ['shop', 'clothing-store'], shoes: ['shop', 'shoe', 'clothing-store'],
  electronics: ['shop', 'mobile-phone'], mobile_phone: ['shop', 'mobile-phone'], computer: ['shop', 'mobile-phone'],
  jewelry: ['shop', 'jewelry-store'], books: ['shop', 'library'], beauty: ['shop', 'beauty', 'hairdresser'],
  hairdresser: ['shop', 'hairdresser'], optician: ['shop', 'optician'], furniture: ['shop', 'furniture'],
  hardware: ['shop', 'hardware'], car: ['shop', 'car'], car_parts: ['shop', 'car'], butcher: ['shop', 'butcher', 'shop'],

  hospital: ['health', 'hospital'], clinic: ['health', 'doctor'], doctors: ['health', 'doctor'],
  dentist: ['health', 'dentist'], pharmacy: ['health', 'pharmacy'], veterinary: ['health', 'veterinary'],

  'place_of_worship:muslim': ['religion', 'religious-muslim'],
  'place_of_worship:christian': ['religion', 'religious-christian'],
  'place_of_worship:jewish': ['religion', 'religious-jewish'],
  'place_of_worship:other': ['religion', 'place-of-worship'],

  school: ['education', 'school'], university: ['education', 'college'], college: ['education', 'college'],
  kindergarten: ['education', 'school'], library: ['education', 'library'],

  fuel: ['transport', 'fuel'], parking: ['transport', 'parking'], bus_station: ['transport', 'bus'],
  bus_stop: ['transport', 'bus'], station: ['transport', 'rail'], aerodrome: ['transport', 'airport'],
  car_repair: ['transport', 'car-repair'], car_wash: ['transport', 'car'], car_rental: ['transport', 'car-rental'],
  charging_station: ['transport', 'charging-station', 'fuel'], taxi: ['transport', 'taxi', 'car'],

  bank: ['finance', 'bank'], atm: ['finance', 'atm', 'bank'], bureau_de_change: ['finance', 'bank'],
  money_transfer: ['finance', 'bank'],

  townhall: ['government', 'town-hall'], police: ['government', 'police'], courthouse: ['government', 'town-hall'],
  fire_station: ['government', 'fire-station'], post_office: ['government', 'post'], embassy: ['government', 'embassy'],
  prison: ['government', 'prison'],

  park: ['leisure', 'park'], garden: ['leisure', 'garden'], stadium: ['leisure', 'stadium'],
  sports_centre: ['leisure', 'pitch'], pitch: ['leisure', 'pitch'], playground: ['leisure', 'playground'],
  swimming_pool: ['leisure', 'swimming'], zoo: ['leisure', 'zoo'],

  museum: ['culture', 'museum'], attraction: ['culture', 'attraction'], monument: ['culture', 'monument'],
  memorial: ['culture', 'monument'], archaeological_site: ['culture', 'ruins', 'monument'], castle: ['culture', 'castle'],
  ruins: ['culture', 'ruins', 'monument'], theatre: ['culture', 'theatre'], cinema: ['culture', 'cinema'],
  artwork: ['culture', 'art-gallery'], viewpoint: ['culture', 'viewpoint', 'attraction'],

  hotel: ['lodging', 'lodging'], hostel: ['lodging', 'lodging'], guest_house: ['lodging', 'lodging'],
  motel: ['lodging', 'lodging'],

  cemetery: ['other', 'cemetery'], toilets: ['other', 'toilet'], information: ['other', 'information'],
  drinking_water: ['other', 'drinking-water'],
};

// Kinds that matter most when looking for a place show one zoom earlier (their tiles already hold them).
const EARLY = ['hospital', 'place_of_worship', 'fuel', 'university', 'college', 'police', 'townhall', 'stadium',
  'museum', 'aerodrome', 'station', 'bus_station', 'mall', 'marketplace', 'archaeological_site', 'castle'];

// --- Icons -------------------------------------------------------------------------------------------------------

function svgToPath(svg) {
  if (/transform=/.test(svg)) return null;
  const view = /viewBox="([\d.\s-]+)"/.exec(svg);
  const size = view ? view[1].trim().split(/\s+/).map(Number) : [0, 0, 15, 15];
  const parts = [];
  for (const m of svg.matchAll(/<path\b[^>]*\bd="([^"]+)"/g)) parts.push(m[1]);
  for (const m of svg.matchAll(/<circle\b([^>]*)>/g)) {
    const a = attrs(m[1]); const r = +a.r, cx = +a.cx, cy = +a.cy;
    parts.push(`M${cx - r},${cy}a${r},${r} 0 1,0 ${2 * r},0a${r},${r} 0 1,0 ${-2 * r},0z`);
  }
  for (const m of svg.matchAll(/<rect\b([^>]*)>/g)) {
    const a = attrs(m[1]);
    parts.push(`M${+(a.x || 0)},${+(a.y || 0)}h${+a.width}v${+a.height}h${-a.width}z`);
  }
  if (/<(polygon|polyline|ellipse|line)\b/.test(svg) || parts.length === 0) return null;
  return { d: parts.join(' '), w: size[2], h: size[3], evenOdd: /fill-rule="evenodd"/.test(svg) };
}

function attrs(text) {
  const out = {};
  for (const m of text.matchAll(/([a-z-]+)="([^"]*)"/g)) out[m[1]] = m[2];
  return out;
}

function findIcon(names) {
  for (const name of names) {
    for (const [dir, prefix] of [[makiDir, ''], [temakiDir, '']]) {
      const file = path.join(dir, prefix + name + '.svg');
      if (fs.existsSync(file)) {
        const parsed = svgToPath(fs.readFileSync(file, 'utf8'));
        if (parsed) return { name, ...parsed };
      }
    }
  }
  return null;
}

const icons = {};
const kinds = {};
const missing = [];
for (const [kind, [category, ...names]] of Object.entries(KINDS)) {
  let icon = findIcon(names) || findIcon(['marker']);
  if (!icon) { missing.push(kind); continue; }
  if (icon.name === 'marker') missing.push(kind + ' (marker)');
  icons[icon.name] = { d: icon.d, w: icon.w, h: icon.h, evenOdd: icon.evenOdd };
  kinds[kind] = { icon: icon.name, category };
}

// --- Style -------------------------------------------------------------------------------------------------------

const PALETTE = {
  light: {
    background: '#F4F1EC', earth: '#F4F1EC',
    landuse_park: '#CBE7C1', landuse_urban_green: '#D5ECCB', landuse_hospital: '#F7DADA', landuse_industrial: '#ECE6EF',
    landuse_school: '#F3E5CC', landuse_beach: '#F6EAC3', landuse_zoo: '#D5ECCB', landuse_aerodrome: '#E5E2EF',
    landuse_pedestrian: '#EFEBE4', landuse_pier: '#E9E4DC', landuse_runway: '#D9D6E3',
    water: '#A7D2F2', water_stream: '#A7D2F2', water_river: '#A7D2F2', buildings: '#E4DED5',
    roads_highway: '#F6AE5E', roads_highway_casing_early: '#D68A3B', roads_highway_casing_late: '#D68A3B',
    roads_major: '#FFE08A', roads_major_casing_early: '#DDBB5F', roads_major_casing_late: '#DDBB5F',
    roads_link: '#FFE08A', roads_link_casing: '#DDBB5F',
    roads_minor: '#FFFFFF', roads_minor_casing: '#D6D0C7', roads_minor_service: '#FFFFFF', roads_minor_service_casing: '#DDD7CE',
    roads_other: '#FFFFFF', roads_pier: '#FFFFFF', roads_rail: '#A6A6A6', roads_runway: '#CFCBDA', roads_taxiway: '#DAD6E4',
    roads_bridges_highway: '#F6AE5E', roads_bridges_highway_casing: '#B8722E', roads_bridges_major: '#FFE08A',
    roads_bridges_major_casing: '#C9A64E', roads_bridges_minor: '#FFFFFF', roads_bridges_minor_casing: '#C9C2B8',
    roads_bridges_link: '#FFE08A', roads_bridges_link_casing: '#C9A64E', roads_bridges_other: '#FFFFFF',
    roads_bridges_other_casing: '#C9C2B8',
    roads_tunnels_highway: '#F9CB98', roads_tunnels_major: '#FFEDB8', roads_tunnels_minor: '#F5F2ED',
    roads_tunnels_link: '#FFEDB8', roads_tunnels_other: '#F5F2ED',
    boundaries: '#A08DB0', boundaries_country: '#8B7699',
  },
  dark: {
    background: '#1B1F24', earth: '#1B1F24',
    landuse_park: '#1E3326', landuse_urban_green: '#20352A', landuse_hospital: '#3A2527', landuse_industrial: '#262433',
    landuse_school: '#332B21', landuse_beach: '#3A3424', landuse_zoo: '#20352A', landuse_aerodrome: '#29283A',
    landuse_pedestrian: '#23272D', landuse_pier: '#262A30', landuse_runway: '#30303F',
    water: '#163049', water_stream: '#163049', water_river: '#163049', buildings: '#2A2E35',
    roads_highway: '#B5733A', roads_highway_casing_early: '#6E4320', roads_highway_casing_late: '#6E4320',
    roads_major: '#8A7843', roads_major_casing_early: '#564A28', roads_major_casing_late: '#564A28',
    roads_link: '#8A7843', roads_link_casing: '#564A28',
    roads_minor: '#3B4049', roads_minor_casing: '#292D33', roads_minor_service: '#353A42', roads_minor_service_casing: '#292D33',
    roads_other: '#353A42', roads_pier: '#353A42', roads_rail: '#5B6068', roads_runway: '#3A3A4C', roads_taxiway: '#33334A',
    roads_bridges_highway: '#B5733A', roads_bridges_highway_casing: '#4F2F16', roads_bridges_major: '#8A7843',
    roads_bridges_major_casing: '#3F3620', roads_bridges_minor: '#3B4049', roads_bridges_minor_casing: '#1F2227',
    roads_bridges_link: '#8A7843', roads_bridges_link_casing: '#3F3620', roads_bridges_other: '#353A42',
    roads_bridges_other_casing: '#1F2227',
    roads_tunnels_highway: '#7A5232', roads_tunnels_major: '#5E5436', roads_tunnels_minor: '#2E3238',
    roads_tunnels_link: '#5E5436', roads_tunnels_other: '#2E3238',
    boundaries: '#6E6283', boundaries_country: '#857899',
  },
};

const TEXT = {
  light: { city: '#1A1A1A', place: '#4E5964', region: '#6B6170', road: '#383838', water: '#3D79AD', address: '#7A7A7A', halo: '#FFFFFF' },
  dark:  { city: '#EEF1F3', place: '#A3AFBA', region: '#A89CB4', road: '#D0D7DC', water: '#8AB4F8', address: '#9AA0A6', halo: '#14171B' },
};

const FONT = { 'Noto Sans Regular': 'Phogram Regular', 'Noto Sans Medium': 'Phogram Medium', 'Noto Sans Italic': 'Phogram Regular' };

/** Swaps Protomaps' fonts for Phogram's anywhere inside a value. */
function swapFonts(value) {
  if (typeof value === 'string') return FONT[value] || value;
  if (Array.isArray(value)) return value.map(swapFonts);
  // Names in two languages carry their fonts inside the text's format options.
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, swapFonts(v)]));
  }
  return value;
}

/** Multiplies the sizes in a zoom curve, or a plain size. */
function scale(value, factor) {
  if (typeof value === 'number') return Math.round(value * factor * 10) / 10;
  if (Array.isArray(value) && value[0] === 'interpolate') {
    return value.map((v, i) => (i >= 4 && i % 2 === 1 && typeof v === 'number' ? Math.round(v * factor * 10) / 10 : v));
  }
  return value;
}

function poiKey() {
  return ['case', ['==', ['get', 'kind'], 'place_of_worship'],
    ['concat', 'place_of_worship:',
      ['match', ['get', 'kind_detail'], ['muslim', 'christian', 'jewish'], ['get', 'kind_detail'], 'other']],
    ['get', 'kind']];
}

function categoryMatch(flavor, field) {
  const pairs = [];
  for (const [kind, { category }] of Object.entries(kinds)) {
    pairs.push(kind, CATEGORIES[category][field === 'text' ? flavor : 'badge']);
  }
  return ['match', poiKey(), ...pairs, CATEGORIES.other[field === 'text' ? flavor : 'badge']];
}

function transform(style, flavor) {
  const colors = PALETTE[flavor];
  const text = TEXT[flavor];
  style.name = 'Phogram ' + flavor;
  style.transition = { duration: 300, delay: 0 };
  for (const layer of style.layers) {
    if (layer.layout && layer.layout['text-font']) layer.layout['text-font'] = swapFonts(layer.layout['text-font']);
    if (layer.layout && layer.layout['text-field']) layer.layout['text-field'] = swapFonts(layer.layout['text-field']);
    const color = colors[layer.id];
    if (color && layer.paint) {
      const key = layer.type === 'background' ? 'background-color' : layer.type === 'fill' ? 'fill-color' : layer.type === 'line' ? 'line-color' : null;
      if (key) layer.paint[key] = color;
    }
    if (layer.type !== 'symbol') continue;
    layer.paint = layer.paint || {};
    const halo = (width) => { layer.paint['text-halo-color'] = text.halo; layer.paint['text-halo-width'] = width; layer.paint['text-halo-blur'] = 0.3; };
    if (layer.id === 'places_locality') {
      layer.paint['text-color'] = text.city; halo(2);
      layer.layout['text-size'] = scale(layer.layout['text-size'], 1.15);
      layer.layout['text-font'] = ['case', ['<=', ['get', 'min_zoom'], 6], ['literal', ['Phogram Bold']], ['literal', ['Phogram Medium']]];
    } else if (layer.id === 'places_subplace') {
      layer.paint['text-color'] = text.place; halo(1.8);
      layer.layout['text-size'] = scale(layer.layout['text-size'], 1.1);
      layer.layout['text-font'] = ['literal', ['Phogram Medium']];
    } else if (layer.id === 'places_region' || layer.id === 'places_country') {
      layer.paint['text-color'] = text.region; halo(2);
      layer.layout['text-font'] = ['literal', ['Phogram Bold']];
    } else if (layer.id.startsWith('roads_labels')) {
      layer.paint['text-color'] = text.road; halo(1.8);
      layer.layout['text-size'] = scale(layer.layout['text-size'], 1.1);
      layer.layout['text-font'] = ['literal', ['Phogram Medium']];
    } else if (layer.id.startsWith('water') || layer.id === 'earth_label_islands') {
      layer.paint['text-color'] = text.water; halo(1.5);
      layer.layout['text-font'] = ['literal', ['Phogram Regular']];
    } else if (layer.id === 'address_label') {
      layer.paint['text-color'] = text.address; halo(1.5);
      layer.layout['text-font'] = ['literal', ['Phogram Regular']];
    } else if (layer.id === 'pois') {
      const textField = layer.layout['text-field'];
      layer.filter = ['all',
        ['in', poiKey(), ['literal', Object.keys(kinds)]],
        ['>=', ['zoom'], ['-', ['get', 'min_zoom'], ['match', ['get', 'kind'], EARLY, 1, 0]]]];
      layer.layout = {
        'icon-image': ['concat', 'pb:', poiKey()],
        'icon-size': ['interpolate', ['linear'], ['zoom'], 13, 0.8, 16, 1, 18, 1.15],
        'icon-padding': 3,
        'symbol-sort-key': ['get', 'min_zoom'],
        'text-field': textField,
        'text-font': ['literal', ['Phogram Medium']],
        'text-size': ['interpolate', ['linear'], ['zoom'], 14, 11, 17, 12.5, 19, 14],
        'text-variable-anchor': ['right', 'left', 'top', 'bottom'],
        'text-radial-offset': 1.25,
        'text-justify': 'auto',
        'text-max-width': 8,
        'text-optional': true,
      };
      layer.paint = { 'text-color': categoryMatch(flavor, 'text') };
      halo(1.6);
    }
  }
  return style;
}

fs.mkdirSync(outDir, { recursive: true });
for (const flavor of ['light', 'dark']) {
  const style = JSON.parse(fs.readFileSync(path.join(stylesDir, `style-${flavor}.json`), 'utf8'));
  fs.writeFileSync(path.join(outDir, `style-${flavor}.json`), JSON.stringify(transform(style, flavor)));
}
const badges = {};
for (const [kind, { icon, category }] of Object.entries(kinds)) badges[kind] = { icon, color: CATEGORIES[category].badge };
fs.writeFileSync(path.join(outDir, 'poi_icons.json'), JSON.stringify({ kinds: badges, icons }));
console.log(`${Object.keys(kinds).length} landmark kinds, ${Object.keys(icons).length} icons`);
if (missing.length) console.log('fell back for: ' + missing.join(', '));
