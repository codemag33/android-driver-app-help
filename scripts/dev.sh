#!/bin/bash
set -e

echo "💻 Yan.Pro — локальная разработка"

# Требования
if ! command -v docker &> /dev/null; then
  echo "❌ Docker не установлен"
  exit 1
fi

# Создание .env для dev
if [ ! -f server/.env ]; then
  cp server/.env.example server/.env
  # Простые значения для разработки
  sed -i 's/JWT_SECRET=.*/JWT_SECRET=dev-secret-12345/' server/.env
  echo "✅ server/.env создан (dev режим)"
fi

# Поднимаем контейнеры
echo "🐳 Запуск сервисов..."
docker-compose -f .github/docker/docker-compose.yml up --build

# Cleanup on Ctrl+C
trap "docker-compose -f .github/docker/docker-compose.yml down" INT
