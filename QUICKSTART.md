# 🚀 Yan.Pro — Быстрый старт

## Локально (5 минут)

```bash
# 1. Требования
# - Docker Desktop
# - 2 ГБ свободной памяти

# 2. Запуск
chmod +x scripts/dev.sh
./scripts/dev.sh

# 3. Откройте в браузере
# Пассажир:    http://localhost:3002/passenger
# Водитель:    http://localhost:3002/driver
# API:         http://localhost:3002/api/auth/login (POST)
```

### Тестовые учётные данные (dev режим)
После запуска скрипт автоматически создаст админа. Используйте эти данные или регистрируйтесь как пассажир/водитель.

## На сервере (20 минут)

### Вариант 1: Docker Compose (рекомендуется)

```bash
# На вашем сервере
git clone https://github.com/your-username/yanpro.git
cd yanpro
chmod +x scripts/deploy.sh
./scripts/deploy.sh

# Скрипт:
# 1. Проверит Docker
# 2. Создаст .env с безопасными значениями
# 3. Поднимет Postgres + Redis + Node.js
# 4. Создаст админа и выведет пароль
# 5. Проверит health
```

### Вариант 2: GitHub Actions + Docker Registry (CI/CD)

1. Запушьте код на GitHub
2. GitHub Actions автоматически:
   - Проверит синтаксис
   - Соберёт Docker образ
   - Запушит в `ghcr.io/username/yanpro:latest`
3. На сервере: `docker pull ghcr.io/username/yanpro:latest && docker run ...`

## Структура проекта

```
yanpro-full/
├── README.md                 ← Полная документация
├── QUICKSTART.md             ← Вы здесь
├── backend/                  ← Node.js сервер
│   ├── src/                  ├─ Express + Socket.IO
│   ├── db/                   ├─ PostgreSQL схема
│   └── package.json          └─ Зависимости
├── pwa/
│   ├── passenger/            ← Веб-приложение пассажира
│   └── driver/               ← Веб-приложение водителя
├── android/                  ← Нативное приложение водителя (Kotlin)
├── .github/
│   ├── workflows/            ← GitHub Actions CI/CD
│   └── docker/               ├─ Dockerfile
│                             └─ docker-compose.yml
├── scripts/
│   ├── deploy.sh             ← Одна команда на сервер
│   └── dev.sh                └─ Локальная разработка
└── docs/
    ├── DEPLOYMENT.md         ← Детальный гайд развёртывания
    └── ARCHITECTURE.md       └─ Архитектура, схемы, API
```

## Основные команды

### Локально
```bash
./scripts/dev.sh              # Запуск с Docker Compose
```

### На сервере
```bash
./scripts/deploy.sh           # Инициализация + запуск
docker-compose logs -f app    # Логи
docker-compose restart app    # Перезагрузка
```

### Остановка/очистка
```bash
docker-compose down           # Остановить все сервисы
docker system prune -a        # Удалить образы (осторожно!)
```

## Ключевые фичи

✅ **Backend**
- Node.js + Express
- Socket.IO для real-time
- PostgreSQL + PostGIS (географические запросы)
- Redis (кэш, geo-индексы)
- JWT-аутентификация

✅ **PWA Пассажира**
- Поиск адреса через прокси-геокодирование
- Ручная установка точки на карте
- Запрос такси / помощи на дороге
- Чат в реальном времени

✅ **PWA Водителя** (стиль Яндекс.Про)
- Входящие заказы с таймером
- Онлайн/оффлайн статус
- История поездок, заработок за день
- Изоляция данных (много водителей не видят друг друга)

✅ **Android приложение**
- Нативный UI (Kotlin, Material Design)
- Работает с тем же бэкендом
- Все features PWA водителя + нативные notifications

## Безопасность "из коробки"

- ✅ Пароли хешируются (bcrypt, 12 раундов)
- ✅ JWT-токены (проверяются на каждое подключение)
- ✅ Socket.IO события привязаны к комнатам (ride_id, assist_id)
- ✅ SQL запросы с параметризацией (no injection)
- ✅ CORS настраивается через env var
- ✅ HTTPS готов (конфиг для Nginx + Let's Encrypt в docs/)

## Масштабирование

**Текущие лимиты (single-server):**
- ~1000-2000 одновременных вебсокетов
- ~10k одновременных live-локаций (Redis GEO)

**Для масштабирования:**
- Load Balancer (Nginx, HAProxy)
- Несколько Node.js инстансов
- Redis Adapter для Socket.IO
- Postgres реплики

**Пример конфига для 10k пользователей:** см. `docs/ARCHITECTURE.md`

## API Quick Reference

### Аутентификация
```bash
# Вход
curl -X POST http://localhost:3002/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"login":"user","password":"pass"}'

# Ответ
{"token":"eyJ...","user":{"id":"...","name":"...","role":"driver"}}
```

### Поиск адреса
```bash
curl http://localhost:3002/api/geocode?q=ул.Ленина
```

### Статистика водителя
```bash
curl http://localhost:3002/api/driver/stats/today \
  -H "Authorization: Bearer TOKEN"
```

### Socket.IO события
```js
// Подключение
const socket = io('http://localhost:3002', {
  auth: { token: JWT_TOKEN }
});

// Запрос такси (пассажир)
socket.emit('ride:request', {
  pickup: {lat: 51.77, lon: 55.10},
  destination: {lat: 51.75, lon: 55.12},
  pickupAddress: 'Главная площадь'
});

// Принять заказ (водитель)
socket.emit('ride:accept', {rideId: 'ride-uuid'});

// Отправить сообщение в чат
socket.emit('chat:send', {
  contextType: 'ride',
  contextId: 'ride-uuid',
  text: 'Я уже близко!'
});
```

## Проблемы и решения

### Приложение не запускается
```bash
# Проверьте статус контейнеров
docker-compose ps

# Посмотрите логи
docker-compose logs app

# Убедитесь, что порты свободны
lsof -i :3002
```

### Геолокация не работает
- На старых браузерах / устройствах пользователь просто вводит адрес вручную
- Ручная установка точки на карте всегда доступна

### Socket.IO не подключается
- Проверьте, что сервер запущен (`curl http://localhost:3002/health`)
- В браузере откройте DevTools → Console → смотрите ошибки
- Проверьте CORS в backend/.env

## Следующие шаги

1. **Выбрать домен:** `yanpro.example.com`
2. **SSL сертификат:** Let's Encrypt (скрипт в docs/DEPLOYMENT.md)
3. **Настроить Nginx:** `.github/docker/nginx.conf`
4. **Добавить иконки:** `pwa/passenger/manifest.json`, `pwa/driver/manifest.json`
5. **Push на GitHub:** настроить GitHub Actions
6. **Развернуть:** `./scripts/deploy.sh`

## Документация

- `README.md` — Полный overview
- `docs/DEPLOYMENT.md` — Пошаговый гайд для сервера
- `docs/ARCHITECTURE.md` — Техническая архитектура, API, схемы БД

## Поддержка

Для вопросов:
1. Проверьте `docs/` каталог
2. Посмотрите логи: `docker-compose logs -f app`
3. Откройте GitHub Issue

---

**Ready? Let's go!** 🚀
