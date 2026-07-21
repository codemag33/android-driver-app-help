const express = require('express');
const db = require('../db');
const { hashPassword, verifyPassword, signToken } = require('../auth');

const router = express.Router();

const ALLOWED_ROLES = ['passenger', 'driver', 'mechanic'];
// 'admin' сознательно не выдаётся через public register — создаётся через db/seed.js

router.post('/register', async (req, res) => {
  try {
    const { login, phone, password, role, name, vehicle_make, vehicle_plate } = req.body;

    if (!login || !password || !name || !role) {
      return res.status(400).json({ error: 'missing_fields' });
    }
    if (!ALLOWED_ROLES.includes(role)) {
      return res.status(400).json({ error: 'invalid_role' });
    }
    if (password.length < 6) {
      return res.status(400).json({ error: 'password_too_short' });
    }

    const existing = await db.query('SELECT id FROM users WHERE login = $1', [login]);
    if (existing.rows.length) {
      return res.status(409).json({ error: 'login_taken' });
    }

    const passwordHash = await hashPassword(password);

    const result = await db.query(
      `INSERT INTO users (login, phone, password_hash, role, name)
       VALUES ($1, $2, $3, $4, $5) RETURNING id, login, role, name`,
      [login, phone || null, passwordHash, role, name]
    );
    const user = result.rows[0];

    if (role === 'driver' || role === 'mechanic') {
      await db.query(
        `INSERT INTO driver_profiles (user_id, vehicle_make, vehicle_plate)
         VALUES ($1, $2, $3)`,
        [user.id, vehicle_make || null, vehicle_plate || null]
      );
    }

    const token = signToken(user);
    res.json({ token, user });
  } catch (err) {
    console.error('[auth/register]', err);
    res.status(500).json({ error: 'server_error' });
  }
});

router.post('/login', async (req, res) => {
  try {
    const { login, password } = req.body;
    if (!login || !password) return res.status(400).json({ error: 'missing_fields' });

    const result = await db.query(
      `SELECT id, login, name, role, password_hash, is_active FROM users WHERE login = $1`,
      [login]
    );
    const user = result.rows[0];
    // Специально не различаем "нет логина" и "неверный пароль" в ответе — чтобы нельзя было
    // перебором узнавать существующие логины.
    if (!user || !user.is_active) {
      return res.status(401).json({ error: 'invalid_credentials' });
    }

    const ok = await verifyPassword(password, user.password_hash);
    if (!ok) return res.status(401).json({ error: 'invalid_credentials' });

    const token = signToken(user);
    res.json({
      token,
      user: { id: user.id, login: user.login, name: user.name, role: user.role },
    });
  } catch (err) {
    console.error('[auth/login]', err);
    res.status(500).json({ error: 'server_error' });
  }
});

module.exports = router;
