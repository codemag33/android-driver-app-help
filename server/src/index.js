require('dotenv').config();
const express = require('express');
const http = require('http');
const cors = require('cors');
const { Server } = require('socket.io');

const db = require('./db');
const authRoutes = require('./routes/auth');
const geocodeRoutes = require('./routes/geocode');
const driverRoutes = require('./routes/driver');
const { requireAuth, requireRole } = require('./middleware/authMiddleware');
const setupSockets = require('./sockets');

const PORT = process.env.PORT || 3002;

const app = express();
app.use(cors({ origin: process.env.CORS_ORIGIN || '*' }));
app.use(express.json());

app.use('/api/auth', authRoutes);
app.use('/api/geocode', geocodeRoutes);
app.use('/api/driver', driverRoutes);

app.get('/health', async (_, res) => {
  try {
    await db.query('SELECT 1');
    res.json({ status: 'ok' });
  } catch {
    res.status(500).json({ status: 'db_unavailable' });
  }
});

app.get('/api/admin/stats', requireAuth, requireRole('admin'), async (_, res) => {
  const rides = await db.query(`SELECT status, count(*) FROM rides GROUP BY status`);
  const assists = await db.query(`SELECT status, count(*) FROM assistance_requests GROUP BY status`);
  const users = await db.query(`SELECT role, count(*) FROM users GROUP BY role`);
  res.json({
    rides: rides.rows,
    assists: assists.rows,
    users: users.rows,
  });
});

app.use('/passenger', express.static('passenger'));
app.use('/driver', express.static('driver'));
app.use('/admin', express.static('admin'));

const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: process.env.CORS_ORIGIN || '*', methods: ['GET', 'POST'] },
});
setupSockets(io);

server.listen(PORT, () => {
  console.log(`\n🚗 Yan.Pro backend on http://0.0.0.0:${PORT}`);
  console.log(`   Health: http://localhost:${PORT}/health\n`);
});
