import http from 'k6/http';
import { sleep } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN;

const adminParams = () => ({
  headers: { Authorization: `Bearer ${ADMIN_TOKEN}`, 'Content-Type': 'application/json' },
  responseType: 'text',
});

/** Creates API keys through the admin API. Paced to stay under NGINX's admin rate limit. */
export function createKeys(count, plan, owner) {
  if (!ADMIN_TOKEN) throw new Error('ADMIN_TOKEN is required');
  const keys = [];
  for (let i = 0; i < count; i++) {
    const res = http.post(`${BASE_URL}/admin/keys`, JSON.stringify({ owner: `${owner}-${i}`, plan }), adminParams());
    if (res.status !== 201) throw new Error(`creating key failed: ${res.status} ${res.body}`);
    const body = res.json();
    keys.push({ id: body.id, secret: body.apiKey });
    if (i % 15 === 14) sleep(1.6);
  }
  return keys;
}

export function revokeKeys(keys) {
  keys.forEach((k, i) => {
    http.del(`${BASE_URL}/admin/keys/${k.id}`, null, adminParams());
    if (i % 15 === 14) sleep(1.6);
  });
}

