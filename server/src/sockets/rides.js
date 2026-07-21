const db = require('../db');

async function createRide({ passengerId, pickup, pickupAddress, destination, destAddress }) {
  const result = await db.query(
    `INSERT INTO rides (passenger_id, pickup, pickup_address, destination, destination_address)
     VALUES ($1, ST_SetSRID(ST_MakePoint($2, $3), 4326), $4, ST_SetSRID(ST_MakePoint($5, $6), 4326), $7)
     RETURNING id, status, created_at`,
    [passengerId, pickup.lon, pickup.lat, pickupAddress || null, destination.lon, destination.lat, destAddress || null]
  );
  return result.rows[0];
}

async function acceptRide(rideId, driverId) {
  const result = await db.query(
    `UPDATE rides SET driver_id = $2, status = 'accepted', accepted_at = now()
     WHERE id = $1 AND status = 'searching'
     RETURNING id, passenger_id, driver_id, status`,
    [rideId, driverId]
  );
  return result.rows[0] || null; // null означает: уже перехвачена другим водителем
}

async function startRide(rideId) {
  await db.query(`UPDATE rides SET status = 'in_progress', started_at = now() WHERE id = $1`, [rideId]);
}

async function finishRide(rideId, price) {
  await db.query(
    `UPDATE rides SET status = 'completed', finished_at = now(), price = COALESCE($2, price) WHERE id = $1`,
    [rideId, price || null]
  );
}

async function cancelRide(rideId, reason) {
  await db.query(
    `UPDATE rides SET status = 'cancelled', finished_at = now(), cancel_reason = $2 WHERE id = $1`,
    [rideId, reason || null]
  );
}

// Общий SELECT, который разворачивает geography-колонки в обычные числа lat/lon —
// иначе клиенту (Android/PWA) пришёл бы нечитаемый бинарный EWKB вместо координат.
const RIDE_SELECT = `
  SELECT id, passenger_id, driver_id, status, price, price_offer,
         ST_Y(pickup::geometry) AS pickup_lat, ST_X(pickup::geometry) AS pickup_lon, pickup_address,
         ST_Y(destination::geometry) AS destination_lat, ST_X(destination::geometry) AS destination_lon,
         destination_address, created_at, accepted_at, started_at, finished_at
  FROM rides`;

async function getRide(rideId) {
  const result = await db.query(`${RIDE_SELECT} WHERE id = $1`, [rideId]);
  return result.rows[0] || null;
}

// Для восстановления сессии при реконнекте: есть ли у пользователя активная поездка?
async function findActiveRideForUser(userId) {
  const result = await db.query(
    `${RIDE_SELECT}
     WHERE (passenger_id = $1 OR driver_id = $1)
       AND status IN ('searching', 'accepted', 'in_progress')
     ORDER BY created_at DESC LIMIT 1`,
    [userId]
  );
  return result.rows[0] || null;
}

async function saveChatMessage({ contextType, contextId, senderId, senderRole, text }) {
  const result = await db.query(
    `INSERT INTO chat_messages (context_type, context_id, sender_id, sender_role, text)
     VALUES ($1, $2, $3, $4, $5) RETURNING id, created_at`,
    [contextType, contextId, senderId, senderRole, text]
  );
  return result.rows[0];
}

async function getChatHistory(contextType, contextId, limit = 50) {
  const result = await db.query(
    `SELECT sender_id, sender_role, text, created_at FROM chat_messages
     WHERE context_type = $1 AND context_id = $2
     ORDER BY created_at ASC LIMIT $3`,
    [contextType, contextId, limit]
  );
  return result.rows;
}

module.exports = {
  createRide, acceptRide, startRide, finishRide, cancelRide, getRide,
  findActiveRideForUser, saveChatMessage, getChatHistory,
};
