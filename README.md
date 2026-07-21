# Yan.Pro — Платформа такси и помощи на дороге

Полнофункциональная платформа для заказа такси и вызова механика, состоящая из:
- **Backend** — Node.js/Express + Socket.IO + PostgreSQL + Redis
- **PWA для пассажира** — реактивный веб-клиент с картой и поиском адреса
- **PWA для водителя/механика** — управление заказами, заработок, история (стиль Яндекс.Про)
- **Android приложение (водитель)** — нативное, взаимодействует с тем же бэкендом

## Быстрый старт (локально)

### 1. Требования
- Node.js 18+
- PostgreSQL 14+ с PostGIS
- Redis
- Docker (опционально)

### 2. Подготовка БД
```bash
sudo -u postgres createuser yanpro -P
sudo -u postgres createdb yanpro -O yanpro
psql "postgres://yanpro:ваш_пароль@localhost/yanpro" -f backend/db/schema.sql
```

### 3. Запуск сервера
```bash
cd backend
cp .env.example .env
# Отредактируйте .env: DATABASE_URL, REDIS_URL, JWT_SECRET (openssl rand -hex 32)
npm install
node db/seed.js admin "МойПароль123" "Администратор"
npm start
```

Сервер слушает на `http://localhost:3002`. 

### 4. Доступ к клиентам
- **Пассажир**: `http://localhost:3002/passenger`
- **Водитель/механик**: `http://localhost:3002/driver`

### 5. Health check
```bash
curl http://localhost:3002/health
```

## Развёртывание на сервер (Docker)

### С Docker Compose
```bash
docker-compose -f .github/docker/docker-compose.yml up -d
```

Это поднимет PostgreSQL, Redis и приложение. Настройте переменные в `.env` перед запуском.

### Вручную (без Docker)
1. Установите PostgreSQL, Redis, Node.js
2. Создайте БД (см. выше)
3. `npm install && npm start` в `backend/`
4. Раздача PWA (`/passenger` и `/driver`) встроена в Express

## GitHub Actions CI/CD

Pipeline автоматически:
- Проверяет синтаксис Node.js кода
- Запускает тесты (если есть)
- Собирает Docker образ на каждый коммит в `main`
- Пушит образ в Docker Registry (GitHub Container Registry)

Конфиг: `.github/workflows/deploy.yml`

## API документация

### Authentication
```
POST /api/auth/login
POST /api/auth/register
```
Оба возвращают JWT-токен, который передаётся через Socket.IO при подключении.

### Socket.IO события (водитель/механик слушает)
```
ride:new_request        — входящий заказ такси
ride:accepted           — заказ принят водителем
ride:started            — водитель начал поездку
ride:finished           — поездка завершена
ride:cancelled          — поездка отменена

assistance:new_request  — входящая заявка на помощь
assistance:accepted     — заявка принята механиком
assistance:finished     — механик завершил работу
assistance:cancelled    — заявка отменена

chat:message            — новое сообщение в чате
chat:history            — история чата для ride/assist_id
```

### REST endpoints (водитель/механик)
```
GET /api/driver/stats/today  — заработок за день, количество поездок
GET /api/driver/history      — последние 30 поездок/заявок
```

### Geocoding (для пассажира)
```
GET /api/geocode?q=...       — поиск адреса (Nominatim через прокси)
GET /api/geocode/reverse?lat=...&lon=...  — обратное геокодирование
```

## Архитектура

### Модель безопасности
- Пароли хешируются bcrypt (12 раундов)
- JWT-токены для сессий (30 дней)
- Токены передаются через Socket.IO `auth` при подключении
- Все socket-события проверяют роль пользователя и принадлежность к комнате

### Масштабирование
- **Redis GEO** для поиска ближайших водителей O(log N) вместо перебора
- **Socket.IO rooms** — каждая поездка (ride_{id}) — отдельная комната, чат/события приватны
- **PostgreSQL PostGIS** для хранения геоточек и истории
- Один Node.js процесс, но архитектура готова к балансировщику + Redis adapter

### Комнаты Socket.IO
```
user_{id}         — персональная комната для restore-сессии
ride_{id}         — комната поездки (пассажир + водитель видят друг друга)
assist_{id}       — комната заявки на помощь (пассажир + механик)
```

## Файловая структура

```
yanpro-full/
├── backend/
│   ├── src/
│   │   ├── index.js          — Express + Socket.IO entry
│   │   ├── db.js             — Postgres pool
│   │   ├── redis.js          — Redis client
│   │   ├── auth.js           — JWT, bcrypt helpers
│   │   ├── routes/           — REST endpoints
│   │   ├── sockets/          — Socket.IO handlers
│   │   └── middleware/       — auth middleware
│   ├── db/
│   │   ├── schema.sql        — PostGIS таблицы
│   │   └── seed.js           — создание админа
│   ├── package.json
│   └── .env.example
├── pwa/
│   ├── passenger/
│   │   ├── index.html        — одно-файловое PWA пассажира
│   │   └── manifest.json
│   └── driver/
│       ├── index.html        — одно-файловое PWA водителя/механика
│       └── manifest.json
├── android/
│   ├── app/                  — Android Studio проект
│   └── build.gradle.kts
├── .github/
│   ├── workflows/
│   │   └── deploy.yml        — CI/CD pipeline
│   └── docker/
│       ├── Dockerfile        — образ бэкенда
│       └── docker-compose.yml
├── scripts/
│   └── deploy.sh             — скрипт для инициализации на сервере
└── docs/
    └── API.md                — документация
```

## Переменные окружения

```bash
# Backend (.env)
PORT=3002
DATABASE_URL=postgres://yanpro:password@localhost:5432/yanpro
REDIS_URL=redis://localhost:6379
JWT_SECRET=your-long-random-string
JWT_EXPIRES_IN=30d
CORS_ORIGIN=https://your-domain.example
```

## Проблемы и решения

### Геолокация на старых устройствах
Если `navigator.geolocation` не отвечает, пассажир всегда может коснуться карты или ввести адрес вручную. Нет fallback-магии — только явные действия пользователя.

### Входящий заказ не показывается
Проверьте:
1. Водитель в статусе `online` (переключатель «На линии»)
2. Redis работает (`redis-cli ping`)
3. Socket.IO соединение активно (в консоли браузера нет ошибок)

### Чат не работает
Убедитесь, что оба пользователя в одной комнате (`ride_{id}` или `assist_{id}`). Сервер физически запрещает писать в чужой чат.

## Лицензия

MIT

## Контакт

Для вопросов и предложений — см. GitHub Issues.
