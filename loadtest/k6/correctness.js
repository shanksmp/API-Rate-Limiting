// Distributed correctness: one API key on the "free" plan (10 requests, refilling 10/min),
// 300 requests fired at once through NGINX, which spreads them across every app instance.
// Exactly 10 may succeed (11 if a refill lands during the burst). Anything more means the
// instances aren't sharing state atomically.
//
// docker compose --profile loadtest run --rm k6 run /scripts/correctness.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, createKeys, revokeKeys } from './lib.js';

const allowed = new Counter('limiter_allowed');
const limited = new Counter('limiter_rejected');
const other = new Counter('unexpected_status');

export const options = {
  discardResponseBodies: true,
  scenarios: {
    burst: { executor: 'shared-iterations', vus: 150, iterations: 300, maxDuration: '30s' },
  },
  thresholds: {
    limiter_allowed: ['count>=10', 'count<=11'],
    limiter_rejected: ['count>=289'],
    unexpected_status: ['count==0'],
  },
};

export function setup() {
  return { keys: createKeys(1, 'free', 'correctness') };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/api/hello`, { headers: { 'X-API-Key': data.keys[0].secret } });
  if (res.status === 200) allowed.add(1);
  else if (res.status === 429) limited.add(1);
  else other.add(1);
  check(res, {
    '429 carries Retry-After': (r) => r.status !== 429 || Number(r.headers['Retry-After']) >= 1,
  });
}

export function teardown(data) {
  revokeKeys(data.keys);
}
