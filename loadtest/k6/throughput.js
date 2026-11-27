// Constant-arrival-rate load test (open model: requests are sent on schedule even if the
// server slows down, so latency numbers don't suffer from coordinated omission).
//
//   TARGET=limited   /api/hello with API keys on the "enterprise" plan: full auth + Redis check
//   TARGET=baseline  /public/ping: same stack without the limiter, to measure its overhead
//
// docker compose --profile loadtest run --rm -e RATE=3000 -e TARGET=limited k6 run /scripts/throughput.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, createKeys, revokeKeys } from './lib.js';

const RATE = parseInt(__ENV.RATE || '2000', 10);
const DURATION = __ENV.DURATION || '60s';
const KEYS = parseInt(__ENV.KEYS || '100', 10);
const TARGET = __ENV.TARGET || 'limited';
const RESULTS_DIR = __ENV.RESULTS_DIR || '/results';

export const options = {
  discardResponseBodies: true,
  setupTimeout: '120s',
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(2000, Math.max(50, RATE / 5)),
      maxVUs: 4000,
    },
  },
  // Thresholds on the {scenario:load} submetrics keep setup/teardown (key creation) out of the numbers
  thresholds: {
    'http_req_failed{scenario:load}': ['rate<0.01'],
    'http_req_duration{scenario:load}': ['p(99)<100'],
    dropped_iterations: ['count<' + Math.ceil(RATE * 0.01 * 60)],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: { target: TARGET },
};

export function setup() {
  return { keys: TARGET === 'limited' ? createKeys(KEYS, 'enterprise', 'loadtest') : [] };
}

export default function (data) {
  if (TARGET === 'baseline') {
    const res = http.get(`${BASE_URL}/public/ping`);
    check(res, { 'status is 200': (r) => r.status === 200 });
    return;
  }
  const key = data.keys[Math.floor(Math.random() * data.keys.length)];
  const res = http.get(`${BASE_URL}/api/hello`, { headers: { 'X-API-Key': key.secret } });
  check(res, { 'status is 200': (r) => r.status === 200 });
}

export function teardown(data) {
  revokeKeys(data.keys);
}

export function handleSummary(data) {
  const m = data.metrics;
  const latency = m['http_req_duration{scenario:load}'].values;
  const seconds = durationSeconds(DURATION);
  const line = {
    target: TARGET,
    targetRate: RATE,
    achievedRps: Math.round(m.iterations.values.count / seconds),
    p50ms: +latency.med.toFixed(2),
    p95ms: +latency['p(95)'].toFixed(2),
    p99ms: +latency['p(99)'].toFixed(2),
    errorRate: +m['http_req_failed{scenario:load}'].values.rate.toFixed(4),
    dropped: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
  };
  return {
    stdout: JSON.stringify(line) + '\n',
    [`${RESULTS_DIR}/${TARGET}-${RATE}.json`]: JSON.stringify(line),
  };
}

function durationSeconds(d) {
  const match = /^(\d+)(s|m)$/.exec(d);
  if (!match) throw new Error(`DURATION must look like 30s or 2m, got ${d}`);
  return Number(match[1]) * (match[2] === 'm' ? 60 : 1);
}
