// Использование: node db/seed.js <login> <пароль> <имя>
// Пример:        node db/seed.js admin "МойСложныйПароль123" "Администратор"
require('dotenv').config();
const db = require('../src/db');
const { hashPassword } = require('../src/auth');

async function main() {
  const [, , login, password, name] = process.argv;
  if (!login || !password) {
    console.log('Использование: node db/seed.js <login> <пароль> [имя]');
    process.exit(1);
  }
  const passwordHash = await hashPassword(password);
  await db.query(
    `INSERT INTO users (login, password_hash, role, name)
     VALUES ($1, $2, 'admin', $3)
     ON CONFLICT (login) DO UPDATE SET password_hash = EXCLUDED.password_hash`,
    [login, passwordHash, name || login]
  );
  console.log(`Админ "${login}" создан/обновлён.`);
  process.exit(0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
