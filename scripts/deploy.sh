#!/bin/bash
set -e

echo "🚀 Yan.Pro — инициализация сервера"

# Проверка требований
if ! command -v docker &> /dev/null; then
  echo "❌ Docker не установлен. Установите Docker и Docker Compose."
  exit 1
fi

# Создание .env если его нет
if [ ! -f server/.env ]; then
  echo "📝 Создание server/.env..."
  cp server/.env.example server/.env

  # Генерируем JWT_SECRET
  JWT_SECRET=$(openssl rand -hex 32)
  sed -i "s/^JWT_SECRET=.*/JWT_SECRET=$JWT_SECRET/" server/.env

  # Генерируем пароль БД
  DB_PASS=$(openssl rand -base64 16)
  sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$DB_PASS/" server/.env

  echo "✅ .env создан с безопасными значениями"
  echo "⚠️  Пожалуйста, отредактируйте server/.env и установите правильные значения:"
  echo "   - DATABASE_URL"
  echo "   - CORS_ORIGIN (адрес вашего домена)"
fi

# Запуск контейнеров
echo "🐳 Запуск Docker контейнеров..."
docker-compose -f .github/docker/docker-compose.yml down 2>/dev/null || true
docker-compose -f .github/docker/docker-compose.yml up -d

# Ждём готовности БД
echo "⏳ Ожидание готовности БД..."
sleep 10

# Создание админа
echo "👤 Создание администратора..."
ADMIN_PASS=$(openssl rand -base64 12)
docker-compose -f .github/docker/docker-compose.yml exec -T app node db/seed.js admin "$ADMIN_PASS" "Администратор"
echo "✅ Администратор создан"
echo "   Логин: admin"
echo "   Пароль: $ADMIN_PASS"
echo "   ⚠️  Сохраните эти учётные данные в безопасном месте!"

# Проверка здоровья
echo "🏥 Проверка здоровья приложения..."
if curl -s http://localhost:3002/health | grep -q '"status":"ok"'; then
  echo "✅ Приложение работает!"
  echo ""
  echo "📱 Доступ:"
  echo "   Backend API: http://localhost:3002"
  echo "   Пассажир:   http://localhost:3002/passenger"
  echo "   Водитель:   http://localhost:3002/driver"
  echo ""
  echo "📋 Рекомендации:"
  echo "   1. Установите SSL сертификат (Let's Encrypt)"
  echo "   2. Настройте Nginx/Caddy как reverse proxy"
   echo "   3. Обновите CORS_ORIGIN в server/.env"
  echo "   4. Добавьте иконки в PWA manifest.json"
else
  echo "❌ Приложение не отвечает. Проверьте логи:"
  echo "   docker-compose -f .github/docker/docker-compose.yml logs app"
  exit 1
fi
