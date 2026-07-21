# Развёртывание Yan.Pro на продакшене

## На собственном VPS

### Требования
- Ubuntu 20.04+ или Debian 11+
- Docker и Docker Compose
- Минимум 2 ГБ RAM, 20 ГБ SSD
- Доменное имя с DNS настройкой

### Шаг 1: Подготовка сервера

```bash
# Обновите систему
sudo apt update && sudo apt upgrade -y

# Установите Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER

# Установите Docker Compose
sudo curl -L "https://github.com/docker/compose/releases/download/v2.20.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# Установите Nginx
sudo apt install -y nginx certbot python3-certbot-nginx
```

### Шаг 2: Клонируйте репозиторий

```bash
cd /app
git clone https://github.com/your-username/yanpro.git
cd yanpro
chmod +x scripts/deploy.sh
```

### Шаг 3: Конфигурация

```bash
# Создайте .env с реальными значениями
nano backend/.env

# Обязательно установите:
# - DATABASE_URL (пароль не "yanpro_dev", а сгенерированный)
# - JWT_SECRET (очень длинный, случайный)
# - CORS_ORIGIN (ваш домен, например https://yanpro.example.com)
```

### Шаг 4: Запуск

```bash
# Инициализируйте приложение
./scripts/deploy.sh

# Проверьте статус
docker-compose -f .github/docker/docker-compose.yml ps
```

### Шаг 5: SSL сертификат

```bash
# Получите сертификат от Let's Encrypt
sudo certbot certonly --standalone -d yanpro.example.com

# Обновите Nginx конфиг
sudo cp .github/docker/nginx.conf /etc/nginx/sites-available/yanpro
sudo sed -i 's/your-domain.example/yanpro.example.com/g' /etc/nginx/sites-available/yanpro
sudo ln -s /etc/nginx/sites-available/yanpro /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl restart nginx

# Auto-renewal
sudo systemctl enable certbot.timer
```

### Шаг 6: Монитор и логи

```bash
# Следите за логами
docker-compose -f .github/docker/docker-compose.yml logs -f app

# Проверка health
curl https://yanpro.example.com/health
```

## GitHub Container Registry + GitHub Actions

### Предварительно
1. Создайте репозиторий на GitHub
2. Включите GitHub Actions (Settings → Actions)
3. Добавьте Secrets (Settings → Secrets and variables):
   - `DEPLOY_HOST` — IP/адрес вашего сервера
   - `DEPLOY_USER` — пользователь для SSH
   - `DEPLOY_SSH_KEY` — private SSH ключ

### Автоматический деплой

После push в `main`:
1. GitHub Actions собирает Docker образ
2. Пушит в `ghcr.io/your-username/yanpro:latest`
3. (Опционально) Деплоит на ваш сервер через SSH

Раскомментируйте шаг `deploy` в `.github/workflows/deploy.yml` и установите Secrets.

## Масштабирование (несколько инстансов)

Для нескольких Node.js процессов за балансировщиком:

```yaml
# docker-compose.yml
services:
  app-1:
    # ... как выше ...
    
  app-2:
    # ... копия app-1 ...
    environment:
      PORT: 3003
      
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ./ssl:/etc/nginx/ssl:ro
    depends_on:
      - app-1
      - app-2
```

Nginx распределяет трафик между инстансами. Redis адаптер Socket.IO синхронизирует сессии между ними.

## Бэкап БД

```bash
# Ежедневный backup Postgres
docker-compose -f .github/docker/docker-compose.yml exec postgres \
  pg_dump -U yanpro yanpro | gzip > /backups/yanpro_$(date +%Y%m%d).sql.gz

# Восстановление
zcat /backups/yanpro_20240721.sql.gz | \
  docker-compose -f .github/docker/docker-compose.yml exec -T postgres \
    psql -U yanpro yanpro
```

## Обновление

```bash
git pull origin main
docker-compose -f .github/docker/docker-compose.yml pull
docker-compose -f .github/docker/docker-compose.yml up -d
```

## Troubleshooting

### Сокет.IO не подключается
```bash
# Проверьте, что Nginx пробрасывает WebSocket
curl -i -N \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  http://localhost:3002/socket.io/?transport=websocket
```

### БД недоступна
```bash
docker-compose -f .github/docker/docker-compose.yml exec postgres psql -U yanpro -d yanpro -c "SELECT 1"
```

### Диск переполнен
```bash
docker system prune -a
docker volume prune
```
