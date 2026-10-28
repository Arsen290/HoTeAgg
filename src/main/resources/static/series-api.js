// API integration and pure helpers for the dashboard. No DOM access, so it can be unit-tested with node.

/** Metric names exactly as accepted by TelemetrySeriesService (ChartMetric.apiName). */
export const METRICS = {
  cpuUtilPct: { label: 'CPU utilization', unit: '%', kind: 'percent' },
  memUtilPct: { label: 'Memory utilization', unit: '%', kind: 'percent' },
  rxBps: { label: 'Incoming traffic (RX)', unit: 'bit/s', kind: 'rate' },
  txBps: { label: 'Outgoing traffic (TX)', unit: 'bit/s', kind: 'rate' },
  powerW: { label: 'Power consumption', unit: 'W', kind: 'power' },
  temperatureC: { label: 'Temperature', unit: '°C', kind: 'temperature' },
};

/** Ranges exactly as accepted by the backend (SeriesRange.apiName). */
export const RANGES = ['1h', '24h', '7d'];

export class ApiError extends Error {
  /** @param {'http'|'network'|'format'} kind */
  constructor(kind, message, status) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
  }
}

/** GET /api/devices/{deviceId}/metrics/{metric}/series?range={range} with safely encoded parts. */
export function buildSeriesUrl(deviceId, metric, range) {
  return `/api/devices/${encodeURIComponent(deviceId)}/metrics/${encodeURIComponent(metric)}/series` +
    `?range=${encodeURIComponent(range)}`;
}

/**
 * Fetches a series and returns [{t: epochMs, v: number}] in the order sent by the backend.
 * Throws ApiError for HTTP errors (using the backend's {"error": ...} text), network failures
 * and unexpected bodies. AbortError is rethrown untouched.
 */
export async function fetchSeries(deviceId, metric, range, { signal, fetchImpl = fetch } = {}) {
  let response;
  try {
    response = await fetchImpl(buildSeriesUrl(deviceId, metric, range), {
      signal,
      headers: { Accept: 'application/json' },
    });
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    throw new ApiError('network', 'Cannot reach the backend. Check that HoTeAgg is running and retry.');
  }

  let body = null;
  try {
    body = await response.json();
  } catch (e) {
    if (e && e.name === 'AbortError') throw e;
    // handled below: non-JSON body
  }

  if (!response.ok) {
    const detail = body && typeof body.error === 'string' ? body.error : response.statusText;
    throw new ApiError('http', `Request failed (HTTP ${response.status}): ${detail || 'unknown error'}`, response.status);
  }
  return parsePoints(body);
}

/** Validates the SeriesResponse shape and converts points; never invents or reorders data. */
export function parsePoints(body) {
  if (!body || !Array.isArray(body.points)) {
    throw new ApiError('format', 'Unexpected response from the backend (no "points" array).');
  }
  return body.points.map((p) => {
    const t = typeof p.timestamp === 'number' ? p.timestamp : Date.parse(p.timestamp);
    if (!Number.isFinite(t) || typeof p.value !== 'number' || !Number.isFinite(p.value)) {
      throw new ApiError('format', 'Unexpected response from the backend (invalid point).');
    }
    return { t, v: p.value };
  });
}

/**
 * Chart data with a break (null) wherever consecutive points are much further apart than is
 * typical, so gaps in telemetry are shown as gaps instead of a misleading straight line.
 */
export function withGaps(points, factor = 3) {
  if (points.length < 3) return points.map((p) => ({ x: p.t, y: p.v }));
  const deltas = points.slice(1).map((p, i) => p.t - points[i].t).sort((a, b) => a - b);
  const median = deltas[Math.floor(deltas.length / 2)];
  const out = [];
  points.forEach((p, i) => {
    if (i > 0 && median > 0 && p.t - points[i - 1].t > median * factor) {
      out.push({ x: (p.t + points[i - 1].t) / 2, y: null });
    }
    out.push({ x: p.t, y: p.v });
  });
  return out;
}

/** Decimal (SI) data-rate formatting, e.g. 845200000 -> "845.2 Mbit/s". */
export function formatRate(bps) {
  const units = ['bit/s', 'Kbit/s', 'Mbit/s', 'Gbit/s', 'Tbit/s'];
  let v = bps;
  let i = 0;
  while (Math.abs(v) >= 1000 && i < units.length - 1) {
    v /= 1000;
    i++;
  }
  return `${trim(v, i === 0 ? 0 : 1)} ${units[i]}`;
}

export function formatValue(metric, value) {
  const m = METRICS[metric];
  if (!m) return String(value);
  switch (m.kind) {
    case 'rate': return formatRate(value);
    case 'percent': return `${trim(value, 1)} %`;
    default: return `${trim(value, 1)} ${m.unit}`;
  }
}

function trim(v, digits) {
  return Number(v.toFixed(digits)).toString();
}

/**
 * "Latest selection wins": starting a new run aborts the previous one, and a finished run that is
 * no longer the latest reports {stale: true} so callers must not render it.
 */
export function createLatestRunner() {
  let id = 0;
  let controller = null;
  return {
    async run(task) {
      if (controller) controller.abort();
      const myId = ++id;
      controller = new AbortController();
      try {
        const value = await task(controller.signal);
        return { stale: myId !== id, value };
      } catch (e) {
        if (myId !== id || (e && e.name === 'AbortError')) return { stale: true };
        throw e;
      }
    },
    cancel() {
      id++;
      if (controller) controller.abort();
      controller = null;
    },
  };
}

