const { verifyToken } = require('../auth');
const geo = require('./geo');
const ridesDb = require('./rides');
const assistDb = require('./assistance');

// ─── Комнаты ────────────────────────────────────────────────────────────
// user_{id}   — персональная комната пользователя (не привязана к конкретному сокету/устройству)
// ride_{id}   — общая комната пассажира и водителя одной поездки — только они видят события друг друга
// assist_{id} — то же для заявки на помощь механика
const userRoom = (id) => `user_${id}`;
const rideRoom = (id) => `ride_${id}`;
const assistRoom = (id) => `assist_${id}`;

function setupSockets(io) {
  // ─── Аутентификация на этапе handshake ──────────────────────────────
  io.use((socket, next) => {
    try {
      const token = socket.handshake.auth?.token;
      if (!token) return next(new Error('no_token'));
      socket.user = verifyToken(token); // { id, role, name, login }
      next();
    } catch {
      next(new Error('invalid_token'));
    }
  });

  io.on('connection', async (socket) => {
    const { id: userId, role, name } = socket.user;
    socket.join(userRoom(userId));
    console.log(`[connect] ${name} (${role}) userId=${userId} socket=${socket.id}`);

    // ─── Восстановление сессии при реконнекте ──────────────────────────
    // Если у пользователя есть активная поездка/заявка — сразу подключаем его обратно
    // в нужную комнату и сообщаем клиенту текущее состояние.
    try {
      const activeRide = await ridesDb.findActiveRideForUser(userId);
      if (activeRide) {
        socket.join(rideRoom(activeRide.id));
        socket.emit('session:restore_ride', activeRide);
      }
      const activeAssist = await assistDb.findActiveAssistForUser(userId);
      if (activeAssist) {
        socket.join(assistRoom(activeAssist.id));
        socket.emit('session:restore_assist', activeAssist);
      }
    } catch (err) {
      console.error('[session:restore] error', err);
    }

    // ─── Обновление геолокации (водитель/механик) ──────────────────────
    socket.on('location:update', async (data) => {
      if (typeof data?.lat !== 'number' || typeof data?.lon !== 'number') return;

      if (role === 'driver' || role === 'mechanic') {
        await geo.setLocation(role, userId, data.lon, data.lat, { socketId: socket.id, name, status: 'online' });
        // Шлём координаты только в комнату конкретной поездки, а не всем подряд.
        if (data.rideId) socket.to(rideRoom(data.rideId)).emit('ride:driver_location', { lat: data.lat, lon: data.lon });
        if (data.assistId) socket.to(assistRoom(data.assistId)).emit('assistance:driver_location', { lat: data.lat, lon: data.lon });
        return;
      }

      // Пассажир тоже может слать своё местоположение — полезно водителю на подъезде к точке А.
      // Рассылаем только в комнату активной поездки, аналогично.
      if (role === 'passenger') {
        if (data.rideId) socket.to(rideRoom(data.rideId)).emit('ride:passenger_location', { lat: data.lat, lon: data.lon });
        if (data.assistId) socket.to(assistRoom(data.assistId)).emit('assistance:passenger_location', { lat: data.lat, lon: data.lon });
      }
    });

    socket.on('driver:status', async (data) => {
      if (role !== 'driver' && role !== 'mechanic') return;
      const status = data?.status === 'online' ? 'online' : 'offline';
      if (status === 'offline') {
        await geo.removeDriver(role, userId);
      } else {
        await geo.setStatus(role, userId, 'online');
      }
    });

    // ─── Поездка: запрос от пассажира ───────────────────────────────────
    socket.on('ride:request', async (data) => {
      if (role !== 'passenger') return;
      if (!data?.pickup || !data?.destination) return;

      try {
        const ride = await ridesDb.createRide({
          passengerId: userId,
          pickup: data.pickup,
          pickupAddress: data.pickupAddress,
          destination: data.destination,
          destAddress: data.destinationAddress,
        });
        socket.join(rideRoom(ride.id));

        // Ищем ближайших свободных водителей через Redis GEO — не рассылаем всем подряд.
        const nearby = await geo.findNearby('driver', data.pickup.lon, data.pickup.lat, 15, 20);
        for (const d of nearby) {
          io.to(userRoom(d.userId)).emit('ride:new_request', {
            rideId: ride.id,
            passengerName: name,
            pickup: data.pickup,
            pickupAddress: data.pickupAddress,
            destination: data.destination,
            destinationAddress: data.destinationAddress,
          });
        }
        socket.emit('ride:created', { rideId: ride.id, driversNotified: nearby.length });
      } catch (err) {
        console.error('[ride:request] error', err);
        socket.emit('error:server', { context: 'ride:request' });
      }
    });

    // ─── Поездка: принятие водителем ────────────────────────────────────
    socket.on('ride:accept', async (data) => {
      if (role !== 'driver') return;
      if (!data?.rideId) return;

      const ride = await ridesDb.acceptRide(data.rideId, userId);
      if (!ride) {
        // Поездку уже забрал другой водитель раньше нас.
        socket.emit('ride:already_taken', { rideId: data.rideId });
        return;
      }

      socket.join(rideRoom(ride.id));
      io.in(userRoom(ride.passenger_id)).socketsJoin(rideRoom(ride.id));

      io.to(rideRoom(ride.id)).emit('ride:accepted', {
        rideId: ride.id,
        driverId: userId,
        driverName: name,
      });

      // Остальным водителям, которым тоже показали заявку, нужно её убрать из списка.
      socket.broadcast.emit('ride:closed_for_others', { rideId: ride.id });
    });

    socket.on('ride:start', async (data) => {
      if (role !== 'driver' || !data?.rideId) return;
      await ridesDb.startRide(data.rideId);
      io.to(rideRoom(data.rideId)).emit('ride:started', { rideId: data.rideId });
    });

    socket.on('ride:finish', async (data) => {
      if (role !== 'driver' || !data?.rideId) return;
      await ridesDb.finishRide(data.rideId, data.price);
      io.to(rideRoom(data.rideId)).emit('ride:finished', { rideId: data.rideId, price: data.price });
      io.socketsLeave(rideRoom(data.rideId));
    });

    socket.on('ride:cancel', async (data) => {
      if (!data?.rideId) return;
      await ridesDb.cancelRide(data.rideId, data.reason);
      io.to(rideRoom(data.rideId)).emit('ride:cancelled', { rideId: data.rideId, by: role });
      io.socketsLeave(rideRoom(data.rideId));
    });

    // ─── Заявка на помощь (механик) — та же логика, отдельные комнаты ──
    socket.on('assistance:request', async (data) => {
      if (role !== 'passenger') return;
      if (!data?.pickup) return;

      const assist = await assistDb.createAssist({
        passengerId: userId,
        pickup: data.pickup,
        carMake: data.carMake,
        phone: data.phone,
        breakdownType: data.breakdownType,
        description: data.description,
      });
      socket.join(assistRoom(assist.id));

      const nearby = await geo.findNearby('mechanic', data.pickup.lon, data.pickup.lat, 25, 20);
      for (const m of nearby) {
        io.to(userRoom(m.userId)).emit('assistance:new_request', {
          assistId: assist.id,
          passengerName: name,
          pickup: data.pickup,
          carMake: data.carMake,
          phone: data.phone,
          breakdownType: data.breakdownType,
          description: data.description,
        });
      }
      socket.emit('assistance:created', { assistId: assist.id, mechanicsNotified: nearby.length });
    });

    socket.on('assistance:accept', async (data) => {
      if (role !== 'mechanic' || !data?.assistId) return;
      const assist = await assistDb.acceptAssist(data.assistId, userId);
      if (!assist) {
        socket.emit('assistance:already_taken', { assistId: data.assistId });
        return;
      }
      socket.join(assistRoom(assist.id));
      io.in(userRoom(assist.passenger_id)).socketsJoin(assistRoom(assist.id));
      io.to(assistRoom(assist.id)).emit('assistance:accepted', {
        assistId: assist.id, mechanicId: userId, mechanicName: name,
      });
      socket.broadcast.emit('assistance:closed_for_others', { assistId: assist.id });
    });

    socket.on('assistance:finish', async (data) => {
      if (role !== 'mechanic' || !data?.assistId) return;
      await assistDb.finishAssist(data.assistId);
      io.to(assistRoom(data.assistId)).emit('assistance:finished', { assistId: data.assistId });
      io.socketsLeave(assistRoom(data.assistId));
    });

    socket.on('assistance:cancel', async (data) => {
      if (!data?.assistId) return;
      await assistDb.cancelAssist(data.assistId);
      io.to(assistRoom(data.assistId)).emit('assistance:cancelled', { assistId: data.assistId, by: role });
      io.socketsLeave(assistRoom(data.assistId));
    });

    // ─── Чат — строго внутри комнаты контекста, никто посторонний не видит ─
    socket.on('chat:send', async (data) => {
      const { contextType, contextId, text } = data || {};
      if (!contextType || !contextId || !text) return;
      if (!['ride', 'assist'].includes(contextType)) return;
      if (text.length > 2000) return; // защита от флуда

      const room = contextType === 'ride' ? rideRoom(contextId) : assistRoom(contextId);
      // Отправляем, только если сокет реально состоит в этой комнате — иначе можно было бы
      // писать в чужой чат, просто угадав id.
      if (!socket.rooms.has(room)) {
        socket.emit('error:server', { context: 'chat:send', reason: 'not_in_room' });
        return;
      }

      const saved = await ridesDb.saveChatMessage({
        contextType, contextId, senderId: userId, senderRole: role, text,
      });

      io.to(room).emit('chat:message', {
        contextType, contextId, senderId: userId, senderRole: role,
        text, createdAt: saved.created_at,
      });
    });

    socket.on('chat:history', async (data) => {
      const { contextType, contextId } = data || {};
      if (!contextType || !contextId) return;
      const room = contextType === 'ride' ? rideRoom(contextId) : assistRoom(contextId);
      if (!socket.rooms.has(room)) return;
      const history = await ridesDb.getChatHistory(contextType, contextId);
      socket.emit('chat:history', { contextType, contextId, messages: history });
    });

    // ─── Отключение ──────────────────────────────────────────────────────
    socket.on('disconnect', async () => {
      if (role === 'driver' || role === 'mechanic') {
        // Не удаляем сразу — TTL в geo.js (10 мин) сам подчистит, если это был просто разрыв связи
        // без явного выхода. Явный выход (driver:status offline) удаляет сразу.
      }
      console.log(`[disconnect] ${name} (${role}) userId=${userId}`);
    });
  });
}

module.exports = setupSockets;
