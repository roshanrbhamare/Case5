// Live-feed load test for POST /v1/events, run against the compose stack:
//
//   docker run --rm --network rostersync_default -v "$PWD/backend/bench:/bench" grafana/k6:0.54.0 run /bench/k6-ingest.js
//   (add -e BATCH=50 for 50 events per request)
//
// Every request carries new, increasing versions, so each event does real work:
// row lock, version check, write, outbox insert, each in its own transaction.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.API_URL || 'http://backend:3000';
const BATCH = Number(__ENV.BATCH || 1);
const OFFICES = ['BLR-WF', 'BLR-ORR', 'PNQ-HJ', 'HYD-HT'];

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '20s',
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(99)', 'max'],
};

export default function () {
  const base = (__VU * 1_000_000 + __ITER * BATCH) * 10;
  const events = Array.from({ length: BATCH }, (_, i) => {
    const n = base + i;
    return {
      type: 'UPDATE',
      employeeId: `L${__VU}-${(__ITER * BATCH + i) % 250}`,
      version: n + 1,
      data: {
        fullName: `Load Person ${n % 250}`,
        email: `load${n % 250}@acme.example`,
        phone: '+919800000000',
        managerId: null,
        officeCode: OFFICES[n % OFFICES.length],
        shiftCode: n % 2 ? 'S0900' : 'NIGHT',
      },
    };
  });
  const res = http.post(`${BASE}/v1/events`, JSON.stringify({ events }), {
    headers: { 'content-type': 'application/json', 'x-api-key': __ENV.API_KEY || 'acme-hr-dev-key' },
  });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
