const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');
const fs = require('fs');

const PORT = process.env.PORT || 3002;
const DATA_FILE = path.join(__dirname, 'data.json');

const app = express();
app.use(cors());
app.use(express.json());

// ─── Data persistence ─────────────────────────────────────────────────────
function loadData() {
  try { return JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')); } catch { return { drivers: {}, rideHistory: [], assistHistory: [] }; }
}
function saveData() {
  fs.writeFileSync(DATA_FILE, JSON.stringify({ drivers: Object.fromEntries(driverRegistry), rideHistory, assistHistory }, null, 2));
}

const persisted = loadData();
const driverRegistry = new Map(Object.entries(persisted.drivers));
let rideHistory = persisted.rideHistory || [];
let assistHistory = persisted.assistHistory || [];

// Default admin driver
if (!driverRegistry.has('admin')) driverRegistry.set('admin', { login: 'admin', pass: '12345', name: 'Admin', role: 'driver' });

// ─── Admin auth ───────────────────────────────────────────────────────────
function adminAuth(req, res, next) {
  const auth = req.headers.authorization || '';
  if (auth.startsWith('Basic ')) {
    const decoded = Buffer.from(auth.slice(6), 'base64').toString();
    const [user, pass] = decoded.split(':');
    if (user === 'admin' && pass === '12345') return next();
  }
  res.status(401).json({ error: 'unauthorized' });
}

// ─── Static ───────────────────────────────────────────────────────────────
app.get('/', (_, res) => res.redirect('/passenger/'));
app.use('/passenger', express.static(path.join(__dirname, 'passenger')));
app.use('/admin', express.static(path.join(__dirname, 'admin')));

// ─── Admin API ────────────────────────────────────────────────────────────
app.get('/api/admin', adminAuth, (_, res) => {
  res.json({
    port: PORT,
    drivers: [...drivers.values()].map(d => ({
      socketId: d.socketId, name: d.name, role: d.role, online: d.online,
      login: d.login || ''
    })),
    passengers: [...passengers.values()].map(p => ({ socketId: p.socketId, id: p.id, name: p.name, rideId: p.rideId })),
    rides: rideHistory.slice(-100),
    assists: assistHistory.slice(-100)
  });
});

app.post('/api/admin/drivers', adminAuth, (req, res) => {
  const { login, pass, name, role } = req.body;
  if (!login || !pass) return res.status(400).json({ error: 'login and pass required' });
  driverRegistry.set(login, { login, pass, name: name || login, role: role || 'driver' });
  saveData();
  res.json({ ok: true });
});

app.post('/api/admin/drivers/:login/delete', adminAuth, (req, res) => {
  driverRegistry.delete(req.params.login);
  saveData();
  res.json({ ok: true });
});

// ─── Health ───────────────────────────────────────────────────────────────
app.get('/health', (_, res) => res.json({ status: 'ok', drivers: drivers.size, passengers: passengers.size, rides: rides.size, assists: assists.size }));

// ─── Socket.IO ────────────────────────────────────────────────────────────
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*', methods: ['GET', 'POST'] }, allowEIO3: true });

const drivers = new Map();
const passengers = new Map();
const rides = new Map();
const assists = new Map();

let rideCounter = 0;
let assistCounter = 0;

function generateRideId() { return `ride_${Date.now()}_${++rideCounter}`; }
function generateAssistId() { return `assist_${Date.now()}_${++assistCounter}`; }
function generatePassengerId() { return `pax_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`; }
function timeStr() { return new Date().toLocaleString('ru-RU', { timeZone: 'Europe/Moscow' }); }

io.on('connection', (socket) => {
  console.log(`[connect] ${socket.id}`);

  // ─── Водитель ─────────────────────────────────────────────────────────────
  socket.on('driver:register', (data) => {
    const driver = {
      id: socket.id,
      name: data.name || 'Водитель',
      role: data.role || 'driver',
      login: data.login || '',
      socketId: socket.id,
      online: true
    };
    drivers.set(socket.id, driver);
    console.log(`[driver:register] ${driver.name} (role=${driver.role}) — drivers online: ${drivers.size}`);
  });

  socket.on('ride:accept', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    const passengerId = data.passengerId;
    const rideId = data.rideId;

    if (passengerId) {
      const passenger = passengers.get(passengerId);
      if (!passenger) return;
      const id = rideId || generateRideId();
      rides.set(id, { id, passengerId, driverId: socket.id, status: 'active', time: timeStr() });
      passenger.rideId = id;
      io.to(passenger.socketId).emit('ride:accepted', { rideId: id, driverName: driver.name, driverLat: 0, driverLon: 0 });
      socket.emit('passenger:ride_accepted', { passengerId, rideId: id });
      console.log(`[ride:accept] Driver ${driver.name} accepted ${passenger.name} — ride ${id}`);
      return;
    }

    if (rideId) {
      const ride = rides.get(rideId);
      if (ride) { ride.driverId = socket.id; ride.status = 'active'; }
      socket.emit('ride:accepted', { rideId });
    }
  });

  socket.on('location:update', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    if (data.rideId) {
      const ride = rides.get(data.rideId);
      if (ride) {
        const passenger = passengers.get(ride.passengerId);
        if (passenger) io.to(passenger.socketId).emit('ride:driver_location', { lat: data.lat, lon: data.lon });
      }
    }
  });

  socket.on('ride:driver_location', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    if (data.passengerId) {
      const passenger = passengers.get(data.passengerId);
      if (passenger) io.to(passenger.socketId).emit('ride:driver_location', { lat: data.lat, lon: data.lon });
    }
  });

  socket.on('chat:message', (data) => {
    const driver = drivers.get(socket.id);
    if (driver) {
      // Водитель → пассажир (мульти-протокол: ищем активный ride/assist по driverId)
      const text = data.text;
      if (data.passengerId) {
        // Новый протокол: ищем ride по passengerId
        for (const [, ride] of rides) {
          if (ride.passengerId === data.passengerId && ride.driverId === socket.id) {
            const passenger = passengers.get(data.passengerId);
            if (passenger) io.to(passenger.socketId).emit('chat:message', { from: 'driver', text, ts: Date.now() });
            return;
          }
        }
      }
      // Старый протокол: ищем ride по rideId
      if (data.rideId) {
        const ride = rides.get(data.rideId);
        if (ride) {
          const passenger = passengers.get(ride.passengerId);
          if (passenger) io.to(passenger.socketId).emit('chat:message', { from: 'driver', text, ts: Date.now() });
        }
      }
      return;
    }
    // Пассажир → водитель
    let passenger = null;
    for (const p of passengers.values()) { if (p.socketId === socket.id) { passenger = p; break; } }
    if (!passenger) return;
    // Ищем активный ride по passengerId
    for (const [, ride] of rides) {
      if (ride.passengerId === passenger.id && ride.driverId) {
        io.to(ride.driverId).emit('passenger:chat', { passengerId: passenger.id, from: 'passenger', text: data.text, ts: Date.now() });
        return;
      }
    }
    // Ищем активный assist по passengerId
    for (const [, assist] of assists) {
      if (assist.passengerId === passenger.id && assist.driverId) {
        io.to(assist.driverId).emit('passenger:chat', { passengerId: passenger.id, from: 'passenger', text: data.text, ts: Date.now() });
        return;
      }
    }
  });

  socket.on('passenger:chat', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    const passengerId = data.passengerId;
    if (!passengerId) return;
    const passenger = passengers.get(passengerId);
    if (!passenger) return;
    io.to(passenger.socketId).emit('chat:message', { from: 'driver', text: data.text, ts: Date.now() });
  });

  socket.on('ride:finish', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    if (data.passengerId) {
      const passenger = passengers.get(data.passengerId);
      if (passenger) {
        io.to(passenger.socketId).emit('ride:finished', {});
        passenger.rideId = null;
        socket.emit('passenger:ride_finished', { passengerId: data.passengerId });
        for (const [id, ride] of rides) {
          if (ride.passengerId === data.passengerId) {
            rideHistory.push({ id, passengerId: ride.passengerId, driverId: driver.name, status: 'completed', time: timeStr() });
            rides.delete(id);
            break;
          }
        }
      }
      return;
    }
    if (data.rideId) {
      const ride = rides.get(data.rideId);
      if (ride) {
        const passenger = passengers.get(ride.passengerId);
        if (passenger) { io.to(passenger.socketId).emit('ride:finished', {}); passenger.rideId = null; }
        rideHistory.push({ id: data.rideId, passengerId: ride.passengerId, driverId: driver.name, status: 'completed', time: timeStr() });
        rides.delete(data.rideId);
      }
      socket.emit('ride:finished', {});
    }
  });

  // ─── Пассажир ─────────────────────────────────────────────────────────────
  socket.on('passenger:register', (data) => {
    const id = generatePassengerId();
    passengers.set(id, { id, name: data.name || 'Пассажир', socketId: socket.id, rideId: null, destination: null });
    console.log(`[passenger:register] ${data.name} (${id}) — passengers online: ${passengers.size}`);
  });

  socket.on('ride:request', (data) => {
    let passenger = null;
    for (const p of passengers.values()) { if (p.socketId === socket.id) { passenger = p; break; } }
    if (!passenger) return;
    const pickup = data.pickup || { lat: 0, lon: 0 };
    const destination = data.destination || { lat: 0, lon: 0 };
    console.log(`[ride:request] ${passenger.name}: ${pickup.lat.toFixed(5)},${pickup.lon.toFixed(5)} → ${destination.lat.toFixed(5)},${destination.lon.toFixed(5)}`);
    for (const driver of drivers.values()) {
      if (driver.online) io.to(driver.socketId).emit('passenger:waiting', { passengerId: passenger.id, passengerName: passenger.name, pickup, destination });
    }
  });

  socket.on('ride:cancel', (data) => {
    let passenger = null;
    for (const p of passengers.values()) { if (p.socketId === socket.id) { passenger = p; break; } }
    if (!passenger) return;
    for (const driver of drivers.values()) {
      if (driver.online) io.to(driver.socketId).emit('passenger:left', { passengerId: passenger.id });
    }
    passenger.rideId = null;
    console.log(`[ride:cancel] ${passenger.name} cancelled`);
  });

  // ─── Помощь на дороге ─────────────────────────────────────────────────────
  socket.on('assistance:request', (data) => {
    let passenger = null;
    for (const p of passengers.values()) { if (p.socketId === socket.id) { passenger = p; break; } }
    if (!passenger) return;

    const assistId = generateAssistId();
    const assist = {
      id: assistId, passengerId: passenger.id, passengerSocketId: socket.id, driverId: null,
      passengerName: passenger.name, pickup: data.pickup || { lat: 0, lon: 0 },
      phone: data.phone || '', carMake: data.carMake || '',
      breakdownType: data.breakdownType || 'unknown', description: data.description || '', status: 'waiting'
    };
    assists.set(assistId, assist);
    assistHistory.push({ id: assistId, passengerName: passenger.name, carMake: assist.carMake, phone: assist.phone, breakdownType: assist.breakdownType, status: 'waiting', time: timeStr() });
    console.log(`[assistance:request] ${passenger.name}: ${assist.carMake} (${assist.breakdownType}) phone=${assist.phone}`);

    for (const driver of drivers.values()) {
      if (driver.online && driver.role === 'mechanic') {
        io.to(driver.socketId).emit('assistance:waiting', {
          assistId, passengerId: passenger.id, passengerName: passenger.name,
          pickup: assist.pickup, carMake: assist.carMake, phone: assist.phone,
          breakdownType: assist.breakdownType, description: assist.description
        });
      }
    }
  });

  socket.on('assistance:accept', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    const assistId = data.assistId;
    if (!assistId) return;
    const assist = assists.get(assistId);
    if (!assist || assist.status !== 'waiting') return;
    assist.driverId = socket.id;
    assist.status = 'active';
    const rec = assistHistory.find(a => a.id === assistId);
    if (rec) { rec.status = 'active'; rec.driverName = driver.name; }
    io.to(assist.passengerSocketId).emit('assistance:accepted', { assistId, driverName: driver.name, driverLat: 0, driverLon: 0 });
    socket.emit('assistance:ride_accepted', { assistId, passengerId: assist.passengerId });
    console.log(`[assistance:accept] ${driver.name} accepted assistance ${assistId}`);
  });

  socket.on('assistance:cancel', (data) => {
    let passenger = null;
    for (const p of passengers.values()) { if (p.socketId === socket.id) { passenger = p; break; } }
    if (!passenger) return;
    const assistId = data.assistId;
    if (!assistId) return;
    const assist = assists.get(assistId);
    if (!assist) return;
    if (assist.driverId) io.to(assist.driverId).emit('assistance:cancelled', { assistId });
    const rec = assistHistory.find(a => a.id === assistId);
    if (rec) rec.status = 'cancelled';
    assists.delete(assistId);
  });

  socket.on('assistance:finish', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    const assistId = data.assistId;
    if (!assistId) return;
    const assist = assists.get(assistId);
    if (!assist) return;
    io.to(assist.passengerSocketId).emit('assistance:finished', { assistId });
    const rec = assistHistory.find(a => a.id === assistId);
    if (rec) rec.status = 'completed';
    assists.delete(assistId);
  });

  socket.on('assistance:driver_location', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    const assistId = data.assistId;
    if (!assistId) return;
    const assist = assists.get(assistId);
    if (!assist) return;
    io.to(assist.passengerSocketId).emit('assistance:driver_location', { assistId, lat: data.lat, lon: data.lon });
  });

  // ─── Disconnect ───────────────────────────────────────────────────────────
  socket.on('disconnect', () => {
    if (drivers.has(socket.id)) {
      for (const [id, assist] of assists) {
        if (assist.driverId === socket.id && assist.status === 'active') {
          io.to(assist.passengerSocketId).emit('assistance:driver_disconnected', { assistId: id });
          assists.delete(id);
        }
      }
      drivers.delete(socket.id);
    }
    for (const [id, passenger] of passengers) {
      if (passenger.socketId === socket.id) {
        if (passenger.rideId) {
          const ride = rides.get(passenger.rideId);
          if (ride && ride.driverId) io.to(ride.driverId).emit('ride:peer_disconnected', {});
        }
        for (const driver of drivers.values()) {
          if (driver.online) io.to(driver.socketId).emit('passenger:left', { passengerId: id });
        }
        passengers.delete(id);
        break;
      }
    }
  });
});

server.listen(PORT, () => {
  console.log(`\n🚗 Yan.Pro server running on http://0.0.0.0:${PORT}`);
  console.log(`   Пассажирский PWA: http://localhost:${PORT}/passenger`);
  console.log(`   Админ-панель:    http://localhost:${PORT}/admin`);
  console.log(`   Health check:    http://localhost:${PORT}/health\n`);
});
