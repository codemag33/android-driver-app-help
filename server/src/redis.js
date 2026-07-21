const Redis = require('ioredis');

const redis = new Redis(process.env.REDIS_URL || 'redis://localhost:6379');

redis.on('error', (err) => console.error('[redis] error', err));
redis.on('connect', () => console.log('[redis] connected'));

module.exports = redis;
