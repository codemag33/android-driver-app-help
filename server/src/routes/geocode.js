const express = require('express');
const router = express.Router();

// Простой in-memory кэш (на проде лучше вынести в Redis, но для геокодинга и такого хватит)
const cache = new Map();
const CACHE_TTL_MS = 10 * 60 * 1000;

// Ограничиваем поиск районом Оренбургской области, чтобы не ловить мусорные результаты
// с другого конца света по совпадению названия улицы.
const VIEWBOX = '53.0,53.5,57.5,50.5'; // lon1,lat1,lon2,lat2 — грубый прямоугольник вокруг области
const USER_AGENT = 'YanPro/1.0 (taxi dispatch app; contact: set-your-email@example.com)';

function getCached(key) {
  const hit = cache.get(key);
  if (!hit) return null;
  if (Date.now() - hit.ts > CACHE_TTL_MS) { cache.delete(key); return null; }
  return hit.data;
}

router.get('/', async (req, res) => {
  const q = (req.query.q || '').trim();
  if (q.length < 3) return res.json({ results: [] });

  const cacheKey = `search:${q.toLowerCase()}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json({ results: cached });

  try {
    const url = `https://nominatim.openstreetmap.org/search?` + new URLSearchParams({
      q, format: 'jsonv2', limit: '6', 'accept-language': 'ru', viewbox: VIEWBOX, bounded: '1',
    });
    const r = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
    if (!r.ok) throw new Error(`nominatim_${r.status}`);
    const data = await r.json();
    const results = data.map((item) => ({
      address: item.display_name,
      lat: parseFloat(item.lat),
      lon: parseFloat(item.lon),
    }));
    cache.set(cacheKey, { ts: Date.now(), data: results });
    res.json({ results });
  } catch (err) {
    console.error('[geocode/search]', err.message);
    res.status(502).json({ error: 'geocode_unavailable', results: [] });
  }
});

router.get('/reverse', async (req, res) => {
  const lat = parseFloat(req.query.lat);
  const lon = parseFloat(req.query.lon);
  if (Number.isNaN(lat) || Number.isNaN(lon)) return res.status(400).json({ error: 'bad_coords' });

  const cacheKey = `reverse:${lat.toFixed(4)},${lon.toFixed(4)}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json(cached);

  try {
    const url = `https://nominatim.openstreetmap.org/reverse?` + new URLSearchParams({
      lat: String(lat), lon: String(lon), format: 'jsonv2', 'accept-language': 'ru',
    });
    const r = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
    if (!r.ok) throw new Error(`nominatim_${r.status}`);
    const data = await r.json();
    const result = { address: data.display_name || `${lat.toFixed(5)}, ${lon.toFixed(5)}` };
    cache.set(cacheKey, { ts: Date.now(), data: result });
    res.json(result);
  } catch (err) {
    console.error('[geocode/reverse]', err.message);
    res.json({ address: `${lat.toFixed(5)}, ${lon.toFixed(5)}` }); // не роняем клиента — просто координаты текстом
  }
});

module.exports = router;
