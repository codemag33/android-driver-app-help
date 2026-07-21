-- Yan.Pro — схема базы данных (PostgreSQL + PostGIS)
-- Установка PostGIS: apt install postgresql-16-postgis-3 (или соответствующая версия)

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS "pgcrypto"; -- для gen_random_uuid()

-- ─── Пользователи ────────────────────────────────────────────────────────
CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    login           text UNIQUE NOT NULL,
    phone           text UNIQUE,
    password_hash   text NOT NULL,
    role            text NOT NULL CHECK (role IN ('passenger', 'driver', 'mechanic', 'admin')),
    name            text NOT NULL,
    is_active       boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- Профиль водителя/механика (доп. данные, не нужные пассажиру)
CREATE TABLE driver_profiles (
    user_id         uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    vehicle_make    text,
    vehicle_plate   text,
    status          text NOT NULL DEFAULT 'offline' CHECK (status IN ('offline', 'online', 'busy')),
    rating          numeric(3,2) NOT NULL DEFAULT 5.00,
    rides_count     integer NOT NULL DEFAULT 0
);

-- ─── Поездки ─────────────────────────────────────────────────────────────
CREATE TABLE rides (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    passenger_id        uuid NOT NULL REFERENCES users(id),
    driver_id           uuid REFERENCES users(id),
    status              text NOT NULL DEFAULT 'searching'
                            CHECK (status IN ('searching', 'accepted', 'in_progress', 'completed', 'cancelled')),
    pickup              geography(Point, 4326) NOT NULL,
    pickup_address      text,
    destination         geography(Point, 4326),
    destination_address text,
    price               numeric(10,2),
    price_offer         numeric(10,2), -- предложенная водителем цена (для торга)
    created_at          timestamptz NOT NULL DEFAULT now(),
    accepted_at         timestamptz,
    started_at          timestamptz,
    finished_at         timestamptz,
    cancel_reason       text
);

CREATE INDEX idx_rides_status ON rides(status);
CREATE INDEX idx_rides_passenger ON rides(passenger_id);
CREATE INDEX idx_rides_driver ON rides(driver_id);
CREATE INDEX idx_rides_pickup_geo ON rides USING GIST(pickup);

-- ─── Заявки на помощь (механики) ─────────────────────────────────────────
CREATE TABLE assistance_requests (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    passenger_id    uuid NOT NULL REFERENCES users(id),
    mechanic_id     uuid REFERENCES users(id),
    status          text NOT NULL DEFAULT 'waiting'
                        CHECK (status IN ('waiting', 'accepted', 'in_progress', 'completed', 'cancelled')),
    pickup          geography(Point, 4326) NOT NULL,
    car_make        text,
    phone           text,
    breakdown_type  text,
    description     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    accepted_at     timestamptz,
    finished_at     timestamptz
);

CREATE INDEX idx_assist_status ON assistance_requests(status);
CREATE INDEX idx_assist_pickup_geo ON assistance_requests USING GIST(pickup);

-- ─── Чат (изолирован по контексту ride/assist — см. sockets/rooms.js) ────
CREATE TABLE chat_messages (
    id              bigserial PRIMARY KEY,
    context_type    text NOT NULL CHECK (context_type IN ('ride', 'assist')),
    context_id      uuid NOT NULL,
    sender_id       uuid NOT NULL REFERENCES users(id),
    sender_role     text NOT NULL,
    text            text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_context ON chat_messages(context_type, context_id, created_at);

-- ─── Дефолтный админ (логин: admin, пароль: сгенерировать через scripts/create-admin.js) ─
-- Пароль НЕ хранится тут в открытом виде — см. db/seed.js
