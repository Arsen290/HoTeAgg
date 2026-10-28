// Run with: node --test src/test/js/
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildSeriesUrl, fetchSeries, parsePoints, withGaps, formatRate, formatValue, createLatestRunner, ApiError, METRICS, RANGES,
} from '../../main/resources/static/series-api.js';

const json = (status, body) => ({
  ok: status >= 200 && status < 300,
  status,
  statusText: String(status),
  json: async () => body,
});

test('metrics and ranges match the backend allowlist', () => {
  assert.deepEqual(Object.keys(METRICS), ['cpuUtilPct', 'memUtilPct', 'rxBps', 'txBps', 'powerW', 'temperatureC']);
  assert.deepEqual(RANGES, ['1h', '24h', '7d']);
});

test('request URL has encoded device/metric and explicit range', () => {
  assert.equal(
    buildSeriesUrl('host-0001', 'cpuUtilPct', '1h'),
    '/api/devices/host-0001/metrics/cpuUtilPct/series?range=1h',
  );
  assert.equal(
    buildSeriesUrl('a/b c?x#', 'rxBps', '7d'),
    '/api/devices/a%2Fb%20c%3Fx%23/metrics/rxBps/series?range=7d',
  );
});

test('fetchSeries calls the right URL and keeps point order', async () => {
  const calls = [];
  const body = {
    deviceId: 'host-0001', metric: 'cpuUtilPct', range: '1h',
    points: [
      { timestamp: '2026-10-28T12:00:00Z', value: 10 },
      { timestamp: '2026-10-28T12:00:10Z', value: 12.5 },
      { timestamp: '2026-10-28T12:00:20Z', value: 11 },
    ],
  };
  const pts = await fetchSeries('host-0001', 'cpuUtilPct', '1h', {
    fetchImpl: async (url) => { calls.push(url); return json(200, body); },
  });
  assert.deepEqual(calls, ['/api/devices/host-0001/metrics/cpuUtilPct/series?range=1h']);
  assert.deepEqual(pts.map((p) => p.v), [10, 12.5, 11]);
  assert.equal(pts[0].t, Date.parse('2026-10-28T12:00:00Z'));
});

test('empty response yields no points (empty state)', async () => {
  const pts = await fetchSeries('d', 'powerW', '1h', {
    fetchImpl: async () => json(200, { deviceId: 'd', metric: 'powerW', range: '1h', points: [] }),
  });
  assert.deepEqual(pts, []);
});

test('HTTP 400 surfaces the backend error message', async () => {
  await assert.rejects(
    fetchSeries('d', 'nope', '1h', {
      fetchImpl: async () => json(400, { error: "Unsupported metric 'nope'. Supported: cpuUtilPct" }),
    }),
    (e) => e instanceof ApiError && e.kind === 'http' && e.status === 400 && /Unsupported metric 'nope'/.test(e.message),
  );
});

test('network failure becomes a recoverable ApiError', async () => {
  await assert.rejects(
    fetchSeries('d', 'cpuUtilPct', '1h', { fetchImpl: async () => { throw new TypeError('Failed to fetch'); } }),
    (e) => e instanceof ApiError && e.kind === 'network',
  );
});

test('malformed responses are rejected without crashing', async () => {
  assert.throws(() => parsePoints({}), ApiError);
  assert.throws(() => parsePoints({ points: [{ timestamp: 'garbage', value: 1 }] }), ApiError);
  await assert.rejects(
    fetchSeries('d', 'cpuUtilPct', '1h', {
      fetchImpl: async () => ({ ok: true, status: 200, json: async () => { throw new SyntaxError('bad json'); } }),
    }),
    (e) => e instanceof ApiError && e.kind === 'format',
  );
});

test('abort is propagated as AbortError, not an ApiError', async () => {
  await assert.rejects(
    fetchSeries('d', 'cpuUtilPct', '1h', {
      fetchImpl: async () => { const e = new Error('aborted'); e.name = 'AbortError'; throw e; },
    }),
    (e) => e.name === 'AbortError',
  );
});

test('gaps are broken, not interpolated; no points are invented', () => {
  const pts = [0, 10, 20, 30, 200].map((s, i) => ({ t: s * 1000, v: i }));
  const out = withGaps(pts);
  assert.equal(out.filter((p) => p.y !== null).length, 5);
  assert.equal(out.filter((p) => p.y === null).length, 1);
});

test('value formatting', () => {
  assert.equal(formatRate(845200000), '845.2 Mbit/s');
  assert.equal(formatRate(999), '999 bit/s');
  assert.equal(formatRate(1.5e9), '1.5 Gbit/s');
  assert.equal(formatValue('cpuUtilPct', 37.54), '37.5 %');
  assert.equal(formatValue('powerW', 312.4), '312.4 W');
  assert.equal(formatValue('temperatureC', 58.2), '58.2 °C');
});

test('stale responses never win over a newer selection', async () => {
  const runner = createLatestRunner();
  let releaseFirst;
  const first = runner.run(() => new Promise((resolve) => { releaseFirst = () => resolve('old'); }));
  const second = runner.run(async () => 'new');
  releaseFirst(); // the old request completes AFTER the new one started
  const [a, b] = await Promise.all([first, second]);
  assert.equal(a.stale, true);
  assert.deepEqual(b, { stale: false, value: 'new' });
});

test('starting a new run aborts the previous request signal', async () => {
  const runner = createLatestRunner();
  let firstSignal;
  const first = runner.run((signal) => { firstSignal = signal; return new Promise(() => {}); });
  runner.run(async () => 1);
  assert.equal(firstSignal.aborted, true);
  runner.cancel();
  first.catch(() => {});
});

