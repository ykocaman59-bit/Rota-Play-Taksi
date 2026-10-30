// RotaPlay Taksi Sunucusu - ek paket gerektirmez (Node 18+)
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');
const PORT = process.env.PORT || 3000;
const ADMIN_KEY = process.env.ADMIN_KEY || 'degistir-beni';
const AUTO_APPROVE = process.env.AUTO_APPROVE === '1';   // test için: şoförü otomatik onayla
const DB_FILE = process.env.DB_FILE || path.join(__dirname, 'data.json');
const RADIUS_KM = 7, REQUEST_TTL_MS = 90000;

let db = { drivers: {}, rides: {} };
try { db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8')); } catch {}
let saveT; const save = () => { clearTimeout(saveT); saveT = setTimeout(() => fs.writeFile(DB_FILE, JSON.stringify(db), () => {}), 300); };

const online = new Map();   // driverId -> { res, lat, lon }  (SSE bağlantısı)
const rid = (n = 8) => crypto.randomBytes(n).toString('hex');
const hash = (pw, salt) => crypto.scryptSync(pw, salt, 32).toString('hex');
const km = (a, b) => { const R = 6371, r = x => x * Math.PI / 180;
  const dLa = r(b.lat - a.lat), dLo = r(b.lon - a.lon);
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(dLo / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h)); };

const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }); res.end(JSON.stringify(obj)); };
const body = req => new Promise(r => { let s = ''; req.on('data', c => { s += c; if (s.length > 1e5) req.destroy(); }); req.on('end', () => { try { r(JSON.parse(s || '{}')); } catch { r({}); } }); });
const sse = (d, ev, data) => { const c = online.get(d); if (c) c.res.write(`event: ${ev}\ndata: ${JSON.stringify(data)}\n\n`); };
const rating = d => d.ratingCount ? Math.round(d.ratingSum / d.ratingCount * 10) / 10 : null;
const activeRideOf = id => Object.values(db.rides).find(r => r.driverId === id && ['accepted', 'picked_up'].includes(r.status));

// Şoföre giden talep özeti: FİYAT / TAHMİNİ ÜCRET YOK
const rideForDriver = (r, d) => ({ id: r.id, passengerName: r.passenger.name.split(' ')[0], pickup: r.pickup, dest: r.dest,
  distanceKm: d ? Math.round(km(d, r.pickup) * 10) / 10 : null });

function dispatch(r) {
  for (const [id, c] of online) {
    const drv = db.drivers[id];
    if (!drv || drv.status !== 'approved' || !c.online || activeRideOf(id) || (r.declined || []).includes(id)) continue;
    if (c.lat != null && km(c, r.pickup) > RADIUS_KM) continue;
    sse(id, 'ride', rideForDriver(r, c.lat != null ? c : null));
  }
  r.expireAt = Date.now() + REQUEST_TTL_MS;
  setTimeout(() => { if (r.status === 'searching' && Date.now() >= r.expireAt) { r.status = 'expired'; save(); for (const id of online.keys()) sse(id, 'ride_gone', { id: r.id }); } }, REQUEST_TTL_MS);
}

const validPoint = p => p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && Math.abs(p.lat) <= 90 && Math.abs(p.lon) <= 180;
const ride = (r, withDriver = true) => { const d = r.driverId && db.drivers[r.driverId]; const c = d && online.get(d.id);
  return { id: r.id, status: r.status, pickup: r.pickup, dest: r.dest, fare: r.fare ?? null,
    driver: withDriver && d && r.status !== 'searching' ? { name: d.name, plate: d.plate, phone: d.phone, rating: rating(d), lat: c?.lat ?? null, lon: c?.lon ?? null } : null,
    rated: !!r.driverRating, driverCancelled: !!r.driverCancelled && r.status === 'searching' }; };

async function api(req, res, url) {
  const p = url.pathname.split('/').filter(Boolean), m = req.method, b = m === 'POST' ? await body(req) : {};

  // ---- Yönetici ----
  if (p[1] === 'admin') {
    if (req.headers['x-admin-key'] !== ADMIN_KEY) return json(res, 403, { error: 'yetkisiz' });
    if (m === 'GET' && p[2] === 'drivers') return json(res, 200, Object.values(db.drivers).map(({ pass, salt, apiKey, ...d }) => d));
    if (m === 'POST' && p[2] === 'approve') { const d = db.drivers[b.id]; if (!d) return json(res, 404, {}); d.status = b.approve === false ? 'blocked' : 'approved'; save(); return json(res, 200, { id: d.id, status: d.status }); }
  }

  // ---- Şoför kayıt / giriş ----
  if (p[1] === 'driver' && m === 'POST' && p[2] === 'register') {
    const { name, phone, plate, password } = b;
    if (!name || !phone || !plate || !password || password.length < 6) return json(res, 400, { error: 'Ad, telefon, plaka ve en az 6 karakterli şifre gerekli' });
    if (Object.values(db.drivers).some(d => d.phone === phone)) return json(res, 409, { error: 'Bu telefon zaten kayıtlı' });
    const id = rid(4), salt = rid(8);
    db.drivers[id] = { id, name, phone, plate: plate.toUpperCase(), salt, pass: hash(password, salt), apiKey: rid(24),
      status: AUTO_APPROVE ? 'approved' : 'pending', ratingSum: 0, ratingCount: 0, created: Date.now() };
    save(); const d = db.drivers[id];
    return json(res, 200, { id, apiKey: d.apiKey, apiPath: `/api/driver/${id}`, status: d.status });
  }
  if (p[1] === 'driver' && m === 'POST' && p[2] === 'login') {
    const d = Object.values(db.drivers).find(x => x.phone === b.phone);
    if (!d || hash(b.password || '', d.salt) !== d.pass) return json(res, 401, { error: 'Telefon veya şifre hatalı' });
    return json(res, 200, { id: d.id, apiKey: d.apiKey, apiPath: `/api/driver/${d.id}`, status: d.status });
  }

  // ---- Şoföre özel adres: /api/driver/:id/... (anahtar o şoföre ait olmalı) ----
  if (p[1] === 'driver' && p[2]) {
    const d = db.drivers[p[2]];
    const key = (req.headers.authorization || '').replace('Bearer ', '') || url.searchParams.get('key');
    if (!d || key !== d.apiKey) return json(res, 401, { error: 'Geçersiz anahtar' });
    const act = p[3];

    if (m === 'GET' && act === 'me') return json(res, 200, { id: d.id, name: d.name, plate: d.plate, status: d.status, rating: rating(d), ratingCount: d.ratingCount, activeRide: activeRideOf(d.id) ? ride(activeRideOf(d.id)) : null });
    if (m === 'GET' && act === 'stream') {
      if (d.status !== 'approved') return json(res, 403, { error: 'Hesabınız henüz onaylanmadı' });
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
      const prev = online.get(d.id); if (prev) try { prev.res.end(); } catch {}
      online.set(d.id, { res, lat: null, lon: null, online: false });
      res.write('event: hello\ndata: {}\n\n');
      const hb = setInterval(() => res.write(': hb\n\n'), 20000);
      req.on('close', () => { clearInterval(hb); if (online.get(d.id)?.res === res) online.delete(d.id); });
      return;
    }
    if (d.status !== 'approved') return json(res, 403, { error: 'Hesabınız henüz onaylanmadı' });
    const c = online.get(d.id);
    if (m === 'POST' && (act === 'online' || act === 'location')) {
      if (!c) return json(res, 409, { error: 'Önce akışa bağlanın' });
      if (Number.isFinite(b.lat) && Number.isFinite(b.lon)) { c.lat = b.lat; c.lon = b.lon; }
      if (act === 'online') {
        c.online = !!b.online;
        if (c.online && !activeRideOf(d.id)) for (const r of Object.values(db.rides)) if (r.status === 'searching' && !(r.declined || []).includes(d.id) && (c.lat == null || km(c, r.pickup) <= RADIUS_KM)) sse(d.id, 'ride', rideForDriver(r, c.lat != null ? c : null));
      }
      return json(res, 200, { ok: true });
    }
    if (m === 'POST' && act === 'rides' && p[4]) {
      const r = db.rides[p[4]], step = p[5];
      if (!r) return json(res, 404, { error: 'Yolculuk yok' });
      if (step === 'accept') {
        // Sunucu tek iş parçacıklı: ilk gelen kazanır, diğerleri 409 alır
        if (r.status !== 'searching') return json(res, 409, { error: 'Bu yolculuğu başka şoför aldı' });
        if (activeRideOf(d.id)) return json(res, 409, { error: 'Zaten aktif yolculuğunuz var' });
        r.status = 'accepted'; r.driverId = d.id; r.acceptedAt = Date.now(); save();
        for (const id of online.keys()) if (id !== d.id) sse(id, 'ride_gone', { id: r.id });
        return json(res, 200, { ...ride(r), passenger: r.passenger });
      }
      if (r.driverId !== d.id) return json(res, 403, { error: 'Bu yolculuk size ait değil' });
      if (step === 'cancel' && r.status === 'accepted') {
        (r.declined = r.declined || []).push(d.id); d.cancelCount = (d.cancelCount || 0) + 1;
        r.driverId = null; r.status = 'searching'; r.driverCancelled = true; save(); dispatch(r);
        return json(res, 200, { ok: true });
      }
      if (step === 'pickup' && r.status === 'accepted') { r.status = 'picked_up'; r.pickedAt = Date.now(); save(); return json(res, 200, ride(r)); }
      if (step === 'complete' && r.status === 'picked_up') {
        const fare = Number(b.fare);                       // taksimetrenin gösterdiği tutar
        if (!(fare >= 0) || fare > 100000) return json(res, 400, { error: 'Taksimetre tutarını girin' });
        r.status = 'completed'; r.fare = Math.round(fare * 100) / 100; r.doneAt = Date.now(); save(); return json(res, 200, ride(r));
      }
      if (step === 'rate' && r.status === 'completed') {
        const s = Math.round(Number(b.rating)); if (!(s >= 1 && s <= 5)) return json(res, 400, { error: '1-5 arası puan verin' });
        r.passengerRating = { stars: s, comment: String(b.comment || '').slice(0, 300) }; save(); return json(res, 200, { ok: true });
      }
      return json(res, 409, { error: 'Bu adım şu an yapılamaz' });
    }
  }

  // ---- Yolcu ----
  if (p[1] === 'rides' && m === 'POST' && !p[2]) {
    const { name, phone, pickup, dest } = b;
    if (!name || !phone || !validPoint(pickup) || !validPoint(dest)) return json(res, 400, { error: 'Ad, telefon, konum ve varış noktası gerekli' });
    const r = { id: rid(5), token: rid(12), status: 'searching', passenger: { name: String(name).slice(0, 60), phone: String(phone).slice(0, 20) },
      pickup: { lat: pickup.lat, lon: pickup.lon, text: String(pickup.text || 'Konumum').slice(0, 150) },
      dest: { lat: dest.lat, lon: dest.lon, text: String(dest.text || 'Seçilen nokta').slice(0, 150) }, created: Date.now() };
    db.rides[r.id] = r; save(); dispatch(r);
    return json(res, 200, { id: r.id, token: r.token });
  }
  if (p[1] === 'rides' && p[2]) {
    const r = db.rides[p[2]], tok = url.searchParams.get('token') || b.token;
    if (!r || r.token !== tok) return json(res, 404, { error: 'Yolculuk bulunamadı' });
    if (m === 'GET') return json(res, 200, ride(r));
    if (m === 'POST' && p[3] === 'cancel') {
      if (!['searching', 'accepted'].includes(r.status)) return json(res, 409, { error: 'İptal edilemez' });
      const was = r.driverId; r.status = 'cancelled'; save();
      for (const id of online.keys()) sse(id, was === id ? 'ride_cancelled' : 'ride_gone', { id: r.id });
      return json(res, 200, { ok: true });
    }
    if (m === 'POST' && p[3] === 'rate' && r.status === 'completed') {
      const s = Math.round(Number(b.rating)); if (!(s >= 1 && s <= 5) || r.driverRating) return json(res, 400, { error: 'Geçersiz puan' });
      r.driverRating = { stars: s, comment: String(b.comment || '').slice(0, 300) };
      const d = db.drivers[r.driverId]; d.ratingSum += s; d.ratingCount++; save(); return json(res, 200, { ok: true });
    }
  }
  json(res, 404, { error: 'bulunamadı' });
}

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' };
http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': '*' }); return res.end(); }
  if (url.pathname.startsWith('/api/')) return api(req, res, url).catch(e => { console.error(e); json(res, 500, { error: 'sunucu hatası' }); });
  const f = path.join(__dirname, 'public', url.pathname === '/' ? 'index.html' : url.pathname);
  if (!f.startsWith(path.join(__dirname, 'public'))) { res.writeHead(403); return res.end(); }
  fs.readFile(f, (e, data) => e ? (res.writeHead(404), res.end('yok')) : (res.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' }), res.end(data)));
}).listen(PORT, () => console.log(`RotaPlay Taksi sunucusu: http://localhost:${PORT}  (admin anahtarı: ${ADMIN_KEY})`));
