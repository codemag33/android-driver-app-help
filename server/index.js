/**
 * Yan.Pro — Socket.IO сервер для связи водителей и пассажиров.
 *
 * Протокол:
 *   Водитель: driver:register, ride:accept, location:update, chat:message, ride:finish
 *   Пассажир: passenger:register, ride:request, location:update, chat:message, ride:cancel
 *
 * Сервер маршрутизирует:
 *   passenger:waiting   → все водители (когда пассажир запрашивает поездку)
 *   passenger:location  → привязанный водитель
 *   ride:driver_location → привязанный пассажир
 *   passenger:chat      ↔ bidirectional
 *   ride:accepted       → пассажир (водитель принял)
 *   ride:finished       ↔ both sides
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');

const PORT = process.env.PORT || 3002;

const app = express();
app.use(cors());
app.use(express.json());

// Пассажирский PWA — раздаём статику
app.use('/passenger', express.static(path.join(__dirname, '..', 'passenger')));

// Админ-дашборд — раздаём статику + JSON API
app.use('/admin', express.static(path.join(__dirname, '..', 'admin')));

app.get('/api/admin', (_, res) => {
  res.json({
    port: PORT,
    drivers: [...drivers.values()].map(d => ({ socketId: d.socketId, name: d.name })),
    passengers: [...passengers.values()].map(p => ({ socketId: p.socketId, id: p.id, name: p.name, rideId: p.rideId })),
    rides: [...rides.values()].map(r => ({ id: r.id, passengerId: r.passengerId, driverId: r.driverId, status: r.status })),
    assists: [...assists.values()].map(a => ({ id: a.id, passengerName: a.passengerName, carMake: a.carMake, breakdownType: a.breakdownType, status: a.status }))
  });
});

// Health check
app.get('/health', (_, res) => res.json({ status: 'ok', drivers: drivers.size, passengers: passengers.size, rides: rides.size, assists: assists.size }));

const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'] },
  allowEIO3: true
});

// ═══ Хранилище данных ════════════════════════════════════════════════════════

/** @type {Map<string, {id:string, name:string, socketId:string, online:boolean}>} */
const drivers = new Map();

/** @type {Map<string, {id:string, name:string, socketId:string, rideId:string|null, destination:any}>} */
const passengers = new Map();

/** @type {Map<string, {id:string, passengerId:string, driverId:string|null, status:string}>} */
const rides = new Map();

/** @type {Map<string, {id:string, passengerId:string, passengerSocketId:string, driverId:string|null, passengerName:string, pickup:any, carMake:string, breakdownType:string, status:string}>} */
const assists = new Map();

let rideCounter = 0;
let assistCounter = 0;

function generateRideId() {
  return `ride_${Date.now()}_${++rideCounter}`;
}

function generateAssistId() {
  return `assist_${Date.now()}_${++assistCounter}`;
}

function generatePassengerId() {
  return `pax_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`;
}

// ═══ Socket.IO ════════════════════════════════════════════════════════════════

io.on('connection', (socket) => {
  console.log(`[connect] ${socket.id}`);

  // ─── Водитель ─────────────────────────────────────────────────────────────

  socket.on('driver:register', (data) => {
    const driver = {
      id: socket.id,
      name: data.name || 'Водитель',
      socketId: socket.id,
      online: true
    };
    drivers.set(socket.id, driver);
    console.log(`[driver:register] ${driver.name} (${socket.id}) — drivers online: ${drivers.size}`);
  });

  socket.on('ride:accept', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;

    const passengerId = data.passengerId;
    const rideId = data.rideId;

    // Мульти-пассажир протокол
    if (passengerId) {
      const passenger = passengers.get(passengerId);
      if (!passenger) return;

      // Создаём поездку
      const id = rideId || generateRideId();
      const ride = {
        id,
        passengerId,
        driverId: socket.id,
        status: 'active'
      };
      rides.set(id, ride);

      // Привязываем к пассажиру
      passenger.rideId = id;

      // Уведомляем пассажира
      io.to(passenger.socketId).emit('ride:accepted', {
        rideId: id,
        driverName: driver.name,
        driverLat: 0,
        driverLon: 0
      });

      // Уведомляем водителя
      socket.emit('passenger:ride_accepted', {
        passengerId,
        rideId: id
      });

      console.log(`[ride:accept] Driver ${driver.name} accepted ${passenger.name} — ride ${id}`);
      return;
    }

    // Старый протокол (один пассажир)
    if (rideId) {
      const ride = rides.get(rideId);
      if (ride) {
        ride.driverId = socket.id;
        ride.status = 'active';
      }
      socket.emit('ride:accepted', { rideId });
    }
  });

  socket.on('location:update', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;

    // Старый протокол — пересылаем пассажирам в этой поездке
    if (data.rideId) {
      const ride = rides.get(data.rideId);
      if (ride) {
        const passenger = passengers.get(ride.passengerId);
        if (passenger) {
          io.to(passenger.socketId).emit('ride:driver_location', {
            lat: data.lat,
            lon: data.lon
          });
        }
      }
    }
  });

  socket.on('ride:driver_location', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;

    if (data.passengerId) {
      const passenger = passengers.get(data.passengerId);
      if (passenger) {
        io.to(passenger.socketId).emit('ride:driver_location', {
          lat: data.lat,
          lon: data.lon
        });
      }
    }
  });

  socket.on('chat:message', (data) => {
    // Водитель → пассажир (старый протокол)
    const driver = drivers.get(socket.id);
    if (driver) {
      if (data.rideId) {
        const ride = rides.get(data.rideId);
        if (ride) {
          const passenger = passengers.get(ride.passengerId);
          if (passenger) {
            io.to(passenger.socketId).emit('chat:message', {
              from: 'driver',
              text: data.text,
              ts: Date.now()
            });
          }
        }
      }
      return;
    }

    // Пассажир → водитель (старый протокол)
    let passenger = null;
    for (const p of passengers.values()) {
      if (p.socketId === socket.id) { passenger = p; break; }
    }
    if (!passenger) return;

    if (passenger.rideId) {
      const ride = rides.get(passenger.rideId);
      if (ride && ride.driverId) {
        io.to(ride.driverId).emit('passenger:chat', {
          passengerId: passenger.id,
          from: 'passenger',
          text: data.text,
          ts: Date.now()
        });
      }
    }
  });

  // Водитель → пассажир (мульти-пассажир протокол)
  socket.on('passenger:chat', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;
    const passengerId = data.passengerId;
    if (!passengerId) return;
    const passenger = passengers.get(passengerId);
    if (!passenger) return;
    io.to(passenger.socketId).emit('chat:message', {
      from: 'driver',
      text: data.text,
      ts: Date.now()
    });
  });

  socket.on('ride:finish', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;

    // Мульти-пассажир
    if (data.passengerId) {
      const passenger = passengers.get(data.passengerId);
      if (passenger) {
        io.to(passenger.socketId).emit('ride:finished', {});
        passenger.rideId = null;
        socket.emit('passenger:ride_finished', { passengerId: data.passengerId });

        // Удаляем поездку
        for (const [id, ride] of rides) {
          if (ride.passengerId === data.passengerId) {
            rides.delete(id);
            break;
          }
        }
      }
      return;
    }

    // Старый протокол
    if (data.rideId) {
      const ride = rides.get(data.rideId);
      if (ride) {
        const passenger = passengers.get(ride.passengerId);
        if (passenger) {
          io.to(passenger.socketId).emit('ride:finished', {});
          passenger.rideId = null;
        }
        rides.delete(data.rideId);
      }
      socket.emit('ride:finished', {});
    }
  });

  // ─── Пассажир ─────────────────────────────────────────────────────────────

  socket.on('passenger:register', (data) => {
    const id = generatePassengerId();
    const passenger = {
      id,
      name: data.name || 'Пассажир',
      socketId: socket.id,
      rideId: null,
      destination: null
    };
    passengers.set(id, passenger);
    console.log(`[passenger:register] ${passenger.name} (${id}) — passengers online: ${passengers.size}`);
  });

  socket.on('ride:request', (data) => {
    // Ищем пассажира по socketId
    let passenger = null;
    for (const p of passengers.values()) {
      if (p.socketId === socket.id) { passenger = p; break; }
    }
    if (!passenger) return;

    const pickup = data.pickup || { lat: 0, lon: 0 };
    const destination = data.destination || { lat: 0, lon: 0 };

    console.log(`[ride:request] ${passenger.name}: ${pickup.lat.toFixed(5)},${pickup.lon.toFixed(5)} → ${destination.lat.toFixed(5)},${destination.lon.toFixed(5)}`);

    // Уведомляем ВСЕХ онлайн-водителей
    for (const driver of drivers.values()) {
      if (driver.online) {
        io.to(driver.socketId).emit('passenger:waiting', {
          passengerId: passenger.id,
          passengerName: passenger.name,
          pickup,
          destination
        });
      }
    }
  });

  socket.on('location:update', (data) => {
    // Пассажир отправляет локацию
    let passenger = null;
    for (const p of passengers.values()) {
      if (p.socketId === socket.id) { passenger = p; break; }
    }
    if (!passenger) return;

    // Пересылаем привязанному водителю
    if (passenger.rideId) {
      const ride = rides.get(passenger.rideId);
      if (ride && ride.driverId) {
        io.to(ride.driverId).emit('passenger:location', {
          passengerId: passenger.id,
          lat: data.lat,
          lon: data.lon
        });
      }
    }
  });

  socket.on('ride:cancel', (data) => {
    let passenger = null;
    for (const p of passengers.values()) {
      if (p.socketId === socket.id) { passenger = p; break; }
    }
    if (!passenger) return;

    // Уведомляем всех водителей что пассажир ушёл
    for (const driver of drivers.values()) {
      if (driver.online) {
        io.to(driver.socketId).emit('passenger:left', {
          passengerId: passenger.id
        });
      }
    }

    passenger.rideId = null;
    console.log(`[ride:cancel] ${passenger.name} cancelled`);
  });

  // ─── Помощь на дороге ─────────────────────────────────────────────────────

  socket.on('assistance:request', (data) => {
    let passenger = null;
    for (const p of passengers.values()) {
      if (p.socketId === socket.id) { passenger = p; break; }
    }
    if (!passenger) return;

    const pickup = data.pickup || { lat: 0, lon: 0 };
    const carMake = data.carMake || '';
    const breakdownType = data.breakdownType || 'unknown';
    const assistId = generateAssistId();

    const assist = {
      id: assistId,
      passengerId: passenger.id,
      passengerSocketId: socket.id,
      driverId: null,
      passengerName: passenger.name,
      pickup,
      carMake,
      breakdownType,
      status: 'waiting'
    };
    assists.set(assistId, assist);

    console.log(`[assistance:request] ${passenger.name}: ${carMake} (${breakdownType}) at ${pickup.lat.toFixed(5)},${pickup.lon.toFixed(5)}`);

    for (const driver of drivers.values()) {
      if (driver.online) {
        io.to(driver.socketId).emit('assistance:waiting', {
          assistId,
          passengerId: passenger.id,
          passengerName: passenger.name,
          pickup,
          carMake,
          breakdownType
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

    io.to(assist.passengerSocketId).emit('assistance:accepted', {
      assistId,
      driverName: driver.name,
      driverLat: 0,
      driverLon: 0
    });

    socket.emit('assistance:ride_accepted', {
      assistId,
      passengerId: assist.passengerId
    });

    console.log(`[assistance:accept] Driver ${driver.name} accepted assistance ${assistId}`);
  });

  socket.on('assistance:cancel', (data) => {
    let passenger = null;
    for (const p of passengers.values()) {
      if (p.socketId === socket.id) { passenger = p; break; }
    }
    if (!passenger) return;

    const assistId = data.assistId;
    if (!assistId) return;

    const assist = assists.get(assistId);
    if (!assist) return;

    if (assist.driverId) {
      io.to(assist.driverId).emit('assistance:cancelled', { assistId });
    }

    assists.delete(assistId);
    console.log(`[assistance:cancel] ${passenger.name} cancelled assistance ${assistId}`);
  });

  socket.on('assistance:finish', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;

    const assistId = data.assistId;
    if (!assistId) return;

    const assist = assists.get(assistId);
    if (!assist) return;

    io.to(assist.passengerSocketId).emit('assistance:finished', { assistId });
    assists.delete(assistId);
    console.log(`[assistance:finish] Driver ${driver.name} finished assistance ${assistId}`);
  });

  socket.on('assistance:driver_location', (data) => {
    const driver = drivers.get(socket.id);
    if (!driver) return;

    const assistId = data.assistId;
    if (!assistId) return;

    const assist = assists.get(assistId);
    if (!assist) return;

    io.to(assist.passengerSocketId).emit('assistance:driver_location', {
      assistId,
      lat: data.lat,
      lon: data.lon
    });
  });

  // ─── Disconnect ───────────────────────────────────────────────────────────

  socket.on('disconnect', () => {
    console.log(`[disconnect] ${socket.id}`);

    // Проверяем — водитель?
    if (drivers.has(socket.id)) {
      // Уведомляем пассажиров об отключении водителя
      for (const [id, assist] of assists) {
        if (assist.driverId === socket.id && assist.status === 'active') {
          io.to(assist.passengerSocketId).emit('assistance:driver_disconnected', { assistId: id });
          assists.delete(id);
        }
      }
      drivers.delete(socket.id);
      console.log(`[driver disconnected] drivers online: ${drivers.size}`);
    }

    // Проверяем — пассажир?
    for (const [id, passenger] of passengers) {
      if (passenger.socketId === socket.id) {
        // Уведомляем водителя
        if (passenger.rideId) {
          const ride = rides.get(passenger.rideId);
          if (ride && ride.driverId) {
            io.to(ride.driverId).emit('ride:peer_disconnected', {});
          }
        }

        // Уведомляем всех водителей
        for (const driver of drivers.values()) {
          if (driver.online) {
            io.to(driver.socketId).emit('passenger:left', {
              passengerId: id
            });
          }
        }

        passengers.delete(id);
        console.log(`[passenger disconnected] passengers online: ${passengers.size}`);
        break;
      }
    }
  });
});

// ═══ Запуск ══════════════════════════════════════════════════════════════════

server.listen(PORT, () => {
  console.log(`\n🚗 Yan.Pro server running on http://0.0.0.0:${PORT}`);
  console.log(`   Пассажирский PWA: http://localhost:${PORT}/passenger`);
  console.log(`   Health check:    http://localhost:${PORT}/health\n`);
});
