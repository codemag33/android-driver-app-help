FROM node:18-alpine

WORKDIR /app

# Копируем backend
COPY server/package.json .
COPY server/src ./src
COPY server/db ./db
COPY server/.env.example .env

# Копируем PWA клиентов для раздачи
RUN mkdir -p passenger driver admin
COPY passenger/index.html passenger/
COPY passenger/manifest.json passenger/
COPY driver/index.html driver/
COPY driver/manifest.json driver/
COPY admin/index.html admin/

# Установка зависимостей
RUN npm install --omit=dev && npm cache clean --force

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "require('http').get('http://localhost:3002/health', (r) => process.exit(r.statusCode === 200 ? 0 : 1))"

EXPOSE 3002

CMD ["npm", "start"]
