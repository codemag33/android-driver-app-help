const db = require('../db');

async function createAssist({ passengerId, pickup, carMake, phone, breakdownType, description }) {
  const result = await db.query(
    `INSERT INTO assistance_requests (passenger_id, pickup, car_make, phone, breakdown_type, description)
     VALUES ($1, ST_SetSRID(ST_MakePoint($2, $3), 4326), $4, $5, $6, $7)
     RETURNING id, status, created_at`,
    [passengerId, pickup.lon, pickup.lat, carMake || null, phone || null, breakdownType || null, description || null]
  );
  return result.rows[0];
}

async function acceptAssist(assistId, mechanicId) {
  const result = await db.query(
    `UPDATE assistance_requests SET mechanic_id = $2, status = 'accepted', accepted_at = now()
     WHERE id = $1 AND status = 'waiting'
     RETURNING id, passenger_id, mechanic_id, status`,
    [assistId, mechanicId]
  );
  return result.rows[0] || null;
}

async function finishAssist(assistId) {
  await db.query(`UPDATE assistance_requests SET status = 'completed', finished_at = now() WHERE id = $1`, [assistId]);
}

async function cancelAssist(assistId) {
  await db.query(`UPDATE assistance_requests SET status = 'cancelled', finished_at = now() WHERE id = $1`, [assistId]);
}

const ASSIST_SELECT = `
  SELECT id, passenger_id, mechanic_id, status, car_make, phone, breakdown_type, description,
         ST_Y(pickup::geometry) AS pickup_lat, ST_X(pickup::geometry) AS pickup_lon,
         created_at, accepted_at, finished_at
  FROM assistance_requests`;

async function getAssist(assistId) {
  const result = await db.query(`${ASSIST_SELECT} WHERE id = $1`, [assistId]);
  return result.rows[0] || null;
}

async function findActiveAssistForUser(userId) {
  const result = await db.query(
    `${ASSIST_SELECT}
     WHERE (passenger_id = $1 OR mechanic_id = $1)
       AND status IN ('waiting', 'accepted', 'in_progress')
     ORDER BY created_at DESC LIMIT 1`,
    [userId]
  );
  return result.rows[0] || null;
}

module.exports = { createAssist, acceptAssist, finishAssist, cancelAssist, getAssist, findActiveAssistForUser };
