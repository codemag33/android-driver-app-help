const express = require('express');
const db = require('../db');
const { requireAuth, requireRole } = require('../middleware/authMiddleware');

const router = express.Router();

// Статистика за сегодня — для карточки заработка на главном экране PWA водителя/механика.
router.get('/stats/today', requireAuth, requireRole('driver', 'mechanic'), async (req, res) => {
  try {
    const rides = await db.query(
      `SELECT COALESCE(SUM(price), 0) AS total, COUNT(*) AS count
       FROM rides
       WHERE driver_id = $1 AND status = 'completed' AND finished_at >= date_trunc('day', now())`,
      [req.user.id]
    );
    const assists = await db.query(
      `SELECT COUNT(*) AS count
       FROM assistance_requests
       WHERE mechanic_id = $1 AND status = 'completed' AND finished_at >= date_trunc('day', now())`,
      [req.user.id]
    );
    res.json({
      earningsToday: parseFloat(rides.rows[0].total),
      ridesToday: parseInt(rides.rows[0].count, 10),
      assistsToday: parseInt(assists.rows[0].count, 10),
    });
  } catch (err) {
    console.error('[driver/stats]', err);
    res.status(500).json({ error: 'server_error' });
  }
});

// Короткая история последних поездок/заявок — для вкладки "История".
router.get('/history', requireAuth, requireRole('driver', 'mechanic'), async (req, res) => {
  try {
    const rides = await db.query(
      `SELECT id, status, price, pickup_address, destination_address, finished_at
       FROM (
         SELECT id, status, price, pickup_address, destination_address, finished_at
         FROM rides WHERE driver_id = $1 AND status IN ('completed', 'cancelled')
       ) t
       ORDER BY finished_at DESC LIMIT 30`,
      [req.user.id]
    );
    res.json({ rides: rides.rows });
  } catch (err) {
    console.error('[driver/history]', err);
    res.status(500).json({ error: 'server_error' });
  }
});

module.exports = router;
