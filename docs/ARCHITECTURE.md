# Архитектура Yan.Pro

## Общая схема

```
┌─────────────────────────────────────────────────────┐
│                   PostgreSQL + PostGIS              │
│           (users, rides, assistance, chat)          │
└──────────────┬──────────────────────────────────────┘
               │
       ┌───────┼───────┐
       │               │
   ┌─────────────┐  ┌──────────┐
   │   Redis     │  │ Node.js  │
   │  (geo, cache)  │ Backend  │
   │             │  │          │
   └─────────────┘  └──────────┘
         │               │
    ┌────┴───────────────┴────┐
    │   Socket.IO + HTTP API   │
    └────┬────────────┬────────┘
         │            │
    ┌────▼──┐    ┌────▼──┐
    │  PWA   │    │ Android
    │Pasсажир│    │ Driver
    │        │    │
    └────────┘    └───────┘
         │
    ┌────▼──┐
    │  PWA   │
    │ Driver │
    │        │
    └────────┘
```

## Компоненты

### Backend (Node.js/Express)

**Запуск:** `npm start` в `/backend`

**Структура:**
- `src/index.js` — entry point, Express app, Socket.IO setup
- `src/db.js` — Postgres connection pool
- `src/redis.js` — Redis client
- `src/auth.js` — JWT, bcrypt utils
- `src/routes/` — REST endpoints (`/api/auth/*`, `/api/geocode`, `/api/driver/stats`)
- `src/sockets/` — Socket.IO event handlers
- `src/middleware/` — auth middleware

**Зависимости:**
- express, socket.io, pg, ioredis, bcrypt, jsonwebtoken, cors

**API:**
- `POST /api/auth/login` — логин, возвращает JWT
- `POST /api/auth/register` — регистрация (role: passenger/driver/mechanic)
- `GET /api/geocode?q=...` — поиск адреса (прокси Nominatim)
- `GET /api/driver/stats/today` — заработок водителя
- `GET /api/driver/history` — история поездок

### Socket.IO Events

**Аутентификация:**
- Токен передаётся через `IO.Options.auth` при подключении
- Сервер проверяет токен в `io.use()` middleware

**Комнаты (rooms):**
- `user_{id}` — персональная комната, для restore сессии
- `ride_{id}` — комната поездки (пассажир + водитель)
- `assist_{id}` — комната заявки на помощь (пассажир + механик)

**События (входящие от клиента):**

Водитель/механик:
```
driver:status         → {status: 'online'|'offline'}
location:update       → {lat, lon, rideId?, assistId?}
ride:accept           → {rideId}
ride:start            → {rideId}
ride:finish           → {rideId, price?}
ride:cancel           → {rideId, reason?}
assistance:accept     → {assistId}
assistance:finish     → {assistId}
assistance:cancel     → {assistId}
chat:send             → {contextType, contextId, text}
chat:history          → {contextType, contextId}
```

Пассажир:
```
location:update       → {lat, lon} (шлёт в текущую комнату)
ride:request          → {pickup: {lat,lon}, destination: {lat,lon}, ...}
assistance:request    → {pickup: {lat,lon}, carMake, phone, ...}
chat:send             → {contextType, contextId, text}
chat:history          → {contextType, contextId}
ride:cancel           → {rideId, reason}
assistance:cancel     → {assistId}
```

**События (исходящие на клиента):**

Входящие заказы:
```
ride:new_request      → {rideId, passengerName, pickup, destination, ...}
ride:already_taken    → {rideId}
ride:closed_for_others → {rideId}
ride:accepted         → {rideId, driverId, driverName}
ride:started          → {rideId}
ride:finished         → {rideId, price?}
ride:cancelled        → {rideId, by}
ride:passenger_location → {lat, lon}
```

Входящие заявки:
```
assistance:new_request → {assistId, passengerName, pickup, carMake, ...}
assistance:already_taken → {assistId}
assistance:closed_for_others → {assistId}
assistance:accepted    → {assistId, mechanicId, mechanicName}
assistance:finished    → {assistId}
assistance:cancelled   → {assistId, by}
assistance:passenger_location → {lat, lon}
```

Сессия & Чат:
```
session:restore_ride   → {id, status, passenger_id, driver_id, ...}
session:restore_assist → {id, status, passenger_id, mechanic_id, ...}
chat:message           → {contextType, contextId, senderId, senderRole, text, createdAt}
chat:history           → {contextType, contextId, messages: [...]}
error:server           → {context, reason?}
```

### Database (PostgreSQL + PostGIS)

**Таблицы:**

```sql
users
├── id (uuid)
├── login (text, unique)
├── password_hash (text, bcrypt)
├── role (text: passenger/driver/mechanic/admin)
├── name, phone, is_active
└── created_at

driver_profiles (user_id FK)
├── vehicle_make, vehicle_plate
├── status (online/busy/offline)
├── rating, rides_count

rides
├── id, passenger_id FK, driver_id FK
├── status (searching/accepted/in_progress/completed/cancelled)
├── pickup (geography Point)
├── destination (geography Point)
├── price, price_offer
├── created_at, accepted_at, started_at, finished_at

assistance_requests
├── id, passenger_id FK, mechanic_id FK
├── status (waiting/accepted/in_progress/completed/cancelled)
├── pickup (geography Point)
├── car_make, phone, breakdown_type, description
├── created_at, accepted_at, finished_at

chat_messages
├── id (bigserial)
├── context_type (ride/assist)
├── context_id (uuid)
├── sender_id, sender_role, text
├── created_at
```

**Индексы:**
- `rides(status, driver_id, passenger_id)`
- `rides GIST(pickup)` — для PostGIS запросов
- `chat_messages(context_type, context_id, created_at)` — для истории по комнате

### Redis

**Ключи:**

```
geo:driver              → GEOHASH set ближайших водителей (lon, lat, user_id)
geo:mechanic            → то же для механиков
driver_meta:driver:{id} → HSET {socketId, name, status, updatedAt}
driver_meta:mechanic:{id} → то же для механиков
```

**Назначение:**
- Поиск ближайших водителей через `GEOSEARCH` — O(log n) вместо O(n) перебора
- Кэш статуса online/offline (TTL 10 минут для auto-cleanup)

### PWA (Passenger)

**Файлы:** `/pwa/passenger/index.html`
- Одно-файловое приложение с HTML, CSS, JS встроенными
- MapLibre GL для карты
- Socket.IO клиент для связи
- REST API для геокодирования и истории

**Функции:**
- Поиск адреса (через прокси `/api/geocode`)
- Ручная установка точки А/Б на карте (drag или клик)
- Запрос такси / помощи на дороге
- Чат с водителем/механиком (изолирован по `ride_id` / `assist_id`)
- История поездок

**Деградация при сбое геолокации:**
Если GPS не отвечает — просто оставляем карту где она, пассажир ставит точку вручную. Нет "рушится".

### PWA (Driver/Mechanic)

**Файлы:** `/pwa/driver/index.html`
- Стиль Яндекс.Про (тёмная тема, жёлтый акцент)
- Карточка входящего заказа с 15-сек таймером
- Активная поездка с кнопками действий
- Заработок за день, история

**Функции:**
- Онлайн/оффлайн (переключатель)
- Просмотр входящих заказов такси и заявок на помощь (в одном очереди, роль из аккаунта определяет тип)
- Чат, локация
- История поездок/заявок

### Android App (Driver)

**Язык:** Kotlin
**Архитектура:** MVVM с LiveData + Socket.IO

**Компоненты:**
- `LoginActivity.kt` — вход/регистрация (REST, JWT)
- `MainActivity.kt` — основной экран (список пассажиров, карта, чат)
- `RideSocketManager.kt` — Socket.IO клиент (все события)
- `RideViewModel.kt` — состояние поездок
- `MapController.kt` — управление слоями карты

**Отличие от PWA:**
- Native UI (Material Design)
- Нативные permissions (GPS, уведомления)
- Глубокая интеграция с ОС (background services)

## Сценарий: Новый заказ такси

```
1. Пассажир нажимает "Заказать"
   → REST: POST /api/auth/login (если не авторизован)
   → Socket.IO: ride:request {pickup, destination, ...}

2. Сервер обрабатывает:
   → Создаёт ride в БД (status='searching')
   → Redis GEOSEARCH: ищет ближайших водителей онлайн
   → Socket.IO: ride:new_request → каждому подходящему водителю

3. Водитель видит карточку заказа (15 сек таймер)
   → Может принять или пропустить

4. Водитель нажимает "Принять"
   → Socket.IO: ride:accept {rideId}
   → Сервер: UPDATE ride SET status='accepted', driver_id=...
   → Оба юзера подключаются в комнату ride_{rideId}
   → Socket.IO: ride:accepted → двоим участникам

5. Остальным водителям отправляется ride:closed_for_others → убирают карточку

6. Водитель едет, отправляет локацию (watchPosition):
   → Socket.IO: location:update {lat, lon, rideId}
   → Сервер: Redis GEO update
   → ride:passenger_location → пассажиру (в комнате ride_{id})

7. Водитель нажимает "Начать поездку"
   → Socket.IO: ride:start {rideId}
   → ride:started → обоим

8. Поездка заканчивается
   → Водитель вводит цену и нажимает "Завершить"
   → Socket.IO: ride:finish {rideId, price}
   → ride:finished → обоим
   → Комната закрывается, оба очищают состояние
```

## Масштабирование

### Текущие ограничения (single-server)
- Redis: ~100k одновременных live-локаций
- Postgres: ~1000 одновременных ride'ов
- Node.js: ~1000-2000 одновременных вебсокетов (зависит от ОС/памяти)

### Для масштабирования (multi-server)
1. **Load Balancer** (Nginx, HAProxy)
   - Распределяет TCP соединения между Node инстансами
   
2. **Redis Adapter** для Socket.IO
   - Синхронизирует rooms между инстансами
   - `npm install @socket.io/redis-adapter`
   
3. **Postgres Replication** (читай-реплики для read-only queries)
   
4. **Кэширование** (Redis, Memcached)
   - geocode результаты
   - stats/history кэши

Примерная конфигурация для 10k одновременных:
- 3x Node.js (3-4 ядра, 2 ГБ каждый)
- 1x Postgres (8 ядер, 16 ГБ)
- 1x Redis (8 ГБ)
- Nginx/HAProxy на фронте

## Безопасность

1. **Аутентификация:**
   - Пароли хешируются bcrypt (12 раундов)
   - JWT-токены (30 дней, refresh-token NOT реализован, при необходимости добавить)
   - Токены не хранятся в куках (уязвимо), клиент сам ими управляет

2. **Socket.IO:**
   - Каждое подключение проверяется `io.use()` middleware
   - Каждый socket-event проверяет, имеет ли клиент право быть в этой комнате (`socket.rooms.has()`)

3. **CORS:**
   - Настраивается через `CORS_ORIGIN` env var
   - На продакшене ограничивать на конкретный домен

4. **SQL Injection:**
   - Используются parameterized queries (`$1, $2` в pg)
   - No string concatenation in SQL

5. **Rate Limiting:**
   - На фронте: таймер 15 сек перед следующей заявкой
   - На бэке: может быть добавлен через redis counters + express-rate-limit

## Мониторинг

**Health check:**
```bash
curl http://localhost:3002/health
# {"status":"ok"}
```

**Логи:**
```bash
docker-compose logs -f app
```

**Метрики (можно добавить):**
- Prometheus `/metrics` endpoint
- Grafana для визуализации
- AlertManager для уведомлений
