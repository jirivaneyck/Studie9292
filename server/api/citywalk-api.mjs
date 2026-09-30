// City Walk API: pin photos and saved trips. No dependencies, Node 20+.
//
//   PUT  /citywalk/api/photos/<id>   (auth) body = JPEG        -> store photo
//   GET  /citywalk/photo/<id>.jpg                              -> photo
//   POST /citywalk/api/trips         (auth) body = trip JSON   -> { id, url }
//   GET  /citywalk/api/trips/<id>                              -> trip JSON
//   GET  /citywalk/trip/<id>                                   -> trip page (map, route, pins, photos)
//
// Env: CITYWALK_TOKEN (required), PORT (4323), DATA_DIR (~/citywalk-data), PUBLIC_BASE.
// Runs behind Caddy, which terminates HTTPS (see server/Caddyfile).

import { createServer } from 'node:http';
import { mkdir, readFile, rename, writeFile, stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';

const TOKEN = process.env.CITYWALK_TOKEN ?? '';
const PORT = Number(process.env.PORT ?? 4323);
const DATA_DIR = process.env.DATA_DIR ?? join(homedir(), 'citywalk-data');
const PUBLIC_BASE = process.env.PUBLIC_BASE ?? 'https://9292games.duckdns.org';
const PHOTO_DIR = join(DATA_DIR, 'photos');
const TRIP_DIR = join(DATA_DIR, 'trips');

const MAX_PHOTO_BYTES = 8 * 1024 * 1024;
const MAX_TRIP_BYTES = 4 * 1024 * 1024;
const MAX_POINTS = 200_000;
const MAX_PINS = 2_000;
const ID_RE = /^[A-Za-z0-9-]{8,64}$/;

if (TOKEN.length < 16) {
  console.error('CITYWALK_TOKEN must be set (at least 16 characters)');
  process.exit(1);
}
await mkdir(PHOTO_DIR, { recursive: true });
await mkdir(TRIP_DIR, { recursive: true });

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function authorized(req) {
  const header = req.headers.authorization ?? '';
  const given = Buffer.from(header.startsWith('Bearer ') ? header.slice(7) : '');
  const expected = Buffer.from(TOKEN);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

async function readBody(req, limit) {
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > limit) throw new HttpError(413, 'Too large');
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new HttpError(413, 'Too large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

/** Write via a temp file so readers never see a half-written file. */
async function writeAtomic(path, data) {
  const tmp = `${path}.${process.pid}.tmp`;
  await writeFile(tmp, data);
  await rename(tmp, path);
}

function send(res, status, body, type = 'application/json', extra = {}) {
  const data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': type, ...extra });
  res.end(data);
}

// ---------- validation ----------

const isLat = (v) => typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= 90;
const isLon = (v) => typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= 180;
const str = (v, max) => (typeof v === 'string' ? v.slice(0, max) : '');
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

/** Rebuilds the trip from only the fields we know, so nothing unexpected gets stored. */
function sanitizeTrip(input) {
  if (typeof input !== 'object' || input === null) throw new HttpError(400, 'Invalid trip');
  let points = 0;
  const segments = (Array.isArray(input.segments) ? input.segments : []).map((seg) => {
    if (!Array.isArray(seg)) throw new HttpError(400, 'Invalid segment');
    return seg.map((p) => {
      if (!Array.isArray(p) || !isLat(p[0]) || !isLon(p[1])) throw new HttpError(400, 'Invalid point');
      if (++points > MAX_POINTS) throw new HttpError(413, 'Too many points');
      return [p[0], p[1], num(p[2])];
    });
  });
  const pinsIn = Array.isArray(input.pins) ? input.pins : [];
  if (pinsIn.length > MAX_PINS) throw new HttpError(413, 'Too many pins');
  const pins = pinsIn.map((p) => {
    if (typeof p !== 'object' || p === null || !isLat(p.lat) || !isLon(p.lon)) {
      throw new HttpError(400, 'Invalid pin');
    }
    const photo = typeof p.photo === 'string' && ID_RE.test(p.photo) ? p.photo : null;
    return {
      lat: p.lat,
      lon: p.lon,
      time: num(p.time),
      note: str(p.note, 500),
      type: p.type === 'goal' ? 'goal' : 'pin',
      photo,
    };
  });
  return {
    name: str(input.name, 100) || 'Walk',
    start: num(input.start),
    end: num(input.end),
    distanceM: num(input.distanceM),
    segments,
    pins,
  };
}

// ---------- trip page ----------

const escapeHtml = (s) =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

function tripPage(trip) {
  // JSON inside <script> must not be able to close the tag
  const json = JSON.stringify(trip).replace(/</g, '\\u003c');
  const km = (trip.distanceM / 1000).toFixed(2);
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escapeHtml(trip.name)} · City Walk</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
  :root { --bg: #0f172a; --fg: #f1f5f9; --muted: #94a3b8; }
  html, body { margin: 0; height: 100%; background: var(--bg); color: var(--fg); font-family: system-ui, sans-serif; }
  body { display: flex; flex-direction: column; }
  header { padding: 12px 16px; }
  h1 { margin: 0; font-size: 20px; }
  .meta { color: var(--muted); font-size: 14px; margin-top: 4px; }
  #map { flex: 1; min-height: 300px; }
  .popup img { display: block; max-width: 240px; max-height: 240px; border-radius: 8px; margin-bottom: 6px; }
  .popup { color: #111; font-size: 14px; white-space: pre-line; }
</style>
</head>
<body>
<header>
  <h1>${escapeHtml(trip.name)}</h1>
  <div class="meta" id="meta">${km} km</div>
</header>
<div id="map"></div>
<script id="trip" type="application/json">${json}</script>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
  const trip = JSON.parse(document.getElementById('trip').textContent);
  const map = L.map('map');
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);

  const bounds = L.latLngBounds([]);
  for (const seg of trip.segments) {
    if (seg.length < 2) continue;
    const line = L.polyline(seg.map(p => [p[0], p[1]]), { color: '#E53935', weight: 5 }).addTo(map);
    bounds.extend(line.getBounds());
  }
  for (const pin of trip.pins) {
    const el = document.createElement('div');
    el.className = 'popup';
    if (pin.photo) {
      const img = document.createElement('img');
      img.src = '/citywalk/photo/' + pin.photo + '.jpg';
      img.loading = 'lazy';
      img.onerror = () => img.remove();
      el.appendChild(img);
    }
    const text = document.createElement('div');
    text.textContent = (pin.type === 'goal' ? '🎯 ' : '') + (pin.note || '');
    el.appendChild(text);
    if (pin.time) {
      const t = document.createElement('small');
      t.textContent = new Date(pin.time).toLocaleString();
      el.appendChild(t);
    }
    L.marker([pin.lat, pin.lon]).addTo(map).bindPopup(el);
    bounds.extend([pin.lat, pin.lon]);
  }
  if (bounds.isValid()) map.fitBounds(bounds, { padding: [24, 24] });
  else map.setView([52.3728, 4.8936], 13);

  if (trip.start) {
    const d = new Date(trip.start).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    document.getElementById('meta').textContent = d + ' · ' + (trip.distanceM / 1000).toFixed(2) + ' km · ' + trip.pins.length + ' pins';
  }
</script>
</body>
</html>`;
}

// ---------- routes ----------

async function handle(req, res) {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname;
  let m;

  if (req.method === 'GET' && path === '/citywalk/api/health') {
    return send(res, 200, { ok: true });
  }

  if ((m = path.match(/^\/citywalk\/api\/photos\/([^/]+)$/)) && req.method === 'PUT') {
    if (!authorized(req)) throw new HttpError(401, 'Unauthorized');
    const id = m[1];
    if (!ID_RE.test(id)) throw new HttpError(400, 'Invalid id');
    const body = await readBody(req, MAX_PHOTO_BYTES);
    if (body.length < 4 || body[0] !== 0xff || body[1] !== 0xd8) throw new HttpError(415, 'JPEG only');
    await writeAtomic(join(PHOTO_DIR, `${id}.jpg`), body);
    return send(res, 200, { id, url: `${PUBLIC_BASE}/citywalk/photo/${id}.jpg` });
  }

  if ((m = path.match(/^\/citywalk\/photo\/([^/]+)\.jpg$/)) && (req.method === 'GET' || req.method === 'HEAD')) {
    const id = m[1];
    if (!ID_RE.test(id)) throw new HttpError(404, 'Not found');
    const file = join(PHOTO_DIR, `${id}.jpg`);
    const info = await stat(file).catch(() => null);
    if (!info) throw new HttpError(404, 'Not found');
    res.writeHead(200, {
      'Content-Type': 'image/jpeg',
      'Content-Length': info.size,
      'Cache-Control': 'public, max-age=31536000, immutable',
    });
    if (req.method === 'HEAD') return res.end();
    return createReadStream(file).pipe(res);
  }

  if (path === '/citywalk/api/trips' && req.method === 'POST') {
    if (!authorized(req)) throw new HttpError(401, 'Unauthorized');
    const body = await readBody(req, MAX_TRIP_BYTES);
    let parsed;
    try {
      parsed = JSON.parse(body.toString('utf8'));
    } catch {
      throw new HttpError(400, 'Invalid JSON');
    }
    const trip = { ...sanitizeTrip(parsed), savedAt: Date.now() };
    const id = randomUUID();
    await writeAtomic(join(TRIP_DIR, `${id}.json`), JSON.stringify(trip));
    return send(res, 201, { id, url: `${PUBLIC_BASE}/citywalk/trip/${id}` });
  }

  if ((m = path.match(/^\/citywalk\/(api\/trips|trip)\/([^/]+)$/)) && req.method === 'GET') {
    const id = m[2];
    if (!ID_RE.test(id)) throw new HttpError(404, 'Not found');
    const raw = await readFile(join(TRIP_DIR, `${id}.json`), 'utf8').catch(() => null);
    if (!raw) throw new HttpError(404, 'Trip not found');
    if (m[1] === 'api/trips') return send(res, 200, raw);
    return send(res, 200, tripPage(JSON.parse(raw)), 'text/html; charset=utf-8');
  }

  throw new HttpError(404, 'Not found');
}

createServer((req, res) => {
  handle(req, res).catch((err) => {
    const status = err instanceof HttpError ? err.status : 500;
    if (status === 500) console.error(err);
    if (!res.headersSent) send(res, status, { error: status === 500 ? 'Server error' : err.message });
    else res.destroy();
  });
}).listen(PORT, '127.0.0.1', () => {
  console.log(`citywalk-api listening on 127.0.0.1:${PORT}, data in ${DATA_DIR}`);
});
