const bcrypt = require('bcrypt');
const jwt = require('jsonwebtoken');

const SALT_ROUNDS = 12;

function hashPassword(plain) {
  return bcrypt.hash(plain, SALT_ROUNDS);
}

function verifyPassword(plain, hash) {
  return bcrypt.compare(plain, hash);
}

function signToken(user) {
  // Кладём в токен только то, что нужно серверу для маршрутизации — не пароль, не телефон.
  return jwt.sign(
    { id: user.id, role: user.role, name: user.name, login: user.login },
    process.env.JWT_SECRET,
    { expiresIn: process.env.JWT_EXPIRES_IN || '30d' }
  );
}

function verifyToken(token) {
  return jwt.verify(token, process.env.JWT_SECRET); // бросает исключение, если невалиден/истёк
}

module.exports = { hashPassword, verifyPassword, signToken, verifyToken };
