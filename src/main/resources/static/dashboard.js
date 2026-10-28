import {
  METRICS, RANGES, ApiError, fetchSeries, withGaps, formatValue, formatRate, createLatestRunner,
} from './series-api.js';

const $ = (id) => document.getElementById(id);
const el = {
  form: $('controls'), device: $('device'), metric: $('metric'), range: $('range'), auto: $('auto'),
  refresh: $('refresh'), status: $('status'), statusText: $('status-text'), overlay: $('overlay'),
  selDevice: $('sel-device'), selMetric: $('sel-metric'), selRange: $('sel-range'),
  summary: $('summary'), canvas: $('chart'),
};

const DEVICE_DEBOUNCE_MS = 600;
const runner = createLatestRunner();
let chart = null;
let hasData = false;
let lastOkAt = null;
let debounceTimer = null;
let autoTimer = null;
let loadSeq = 0;

for (const [key, m] of Object.entries(METRICS)) {
  el.metric.add(new Option(`${m.label} (${m.unit})`, key));
}

const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const axisFmt = (range) => new Intl.DateTimeFormat(
  undefined,
  range === '7d' ? { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }
    : { hour: '2-digit', minute: '2-digit' },
);
const fullFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });

function selection() {
  return { device: el.device.value.trim(), metric: el.metric.value, range: el.range.value };
}

function setStatus(kind, text) {
  el.status.className = `status status-${kind}`;
  el.statusText.textContent = text;
}

function showOverlay(kind, text) {
  el.overlay.className = `overlay ${kind}`;
  el.overlay.textContent = text;
  el.overlay.hidden = false;
}

function hideOverlay() {
  el.overlay.hidden = true;
}

function ensureChart() {
  if (chart) return chart;
  chart = new Chart(el.canvas, {
    type: 'line',
    data: { datasets: [{ data: [], borderColor: '#1f6feb', backgroundColor: 'rgba(31,111,235,0.12)', borderWidth: 2, pointRadius: 2, pointHoverRadius: 5, spanGaps: false, fill: true, tension: 0 }] },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      parsing: false,
      interaction: { mode: 'nearest', axis: 'x', intersect: false },
      plugins: { legend: { display: false }, title: { display: true, text: '' }, tooltip: { callbacks: {} } },
      scales: {
        x: { type: 'linear', title: { display: true, text: 'Time (local)' }, ticks: { maxTicksLimit: 8 } },
        y: { title: { display: true, text: '' }, ticks: {} },
      },
    },
  });
  return chart;
}

function renderChart(points, { device, metric, range }) {
  const m = METRICS[metric];
  const c = ensureChart();
  const fmt = axisFmt(range);
  c.data.datasets[0].data = withGaps(points);
  c.data.datasets[0].label = m.label;
  c.options.plugins.title.text = `${m.label} — ${device} — last ${range}`;
  c.options.scales.x.ticks.callback = (v) => fmt.format(new Date(v));
  c.options.scales.y.title.text = `${m.label} (${m.unit})`;
  c.options.scales.y.ticks.callback = (v) => (m.kind === 'rate' ? formatRate(v) : v);
  c.options.scales.y.min = m.kind === 'percent' ? 0 : undefined;
  c.options.scales.y.max = m.kind === 'percent' ? 100 : undefined;
  c.options.scales.y.beginAtZero = m.kind !== 'temperature';
  c.options.plugins.tooltip.callbacks.title = (items) => fullFmt.format(new Date(items[0].parsed.x));
  c.options.plugins.tooltip.callbacks.label = (item) => `${m.label}: ${formatValue(metric, item.parsed.y)}`;
  c.update();
}

function clearChart() {
  if (chart) {
    chart.data.datasets[0].data = [];
    chart.update();
  }
  hasData = false;
  el.summary.textContent = '';
}

/** Loads the current selection. `background` = periodic refresh (keeps showing old data while loading). */
async function load({ background = false } = {}) {
  clearTimeout(debounceTimer);
  clearTimeout(autoTimer);
  const mySeq = ++loadSeq;
  const sel = selection();
  el.selDevice.textContent = sel.device || '–';
  el.selMetric.textContent = METRICS[sel.metric].label;
  el.selRange.textContent = sel.range;

  if (!sel.device) {
    runner.cancel();
    clearChart();
    showOverlay('error', 'Enter a device ID.');
    setStatus('error', 'No device ID');
    el.refresh.disabled = false;
    return;
  }
  if (!RANGES.includes(sel.range) || !METRICS[sel.metric]) return;

  el.refresh.disabled = true;
  setStatus('loading', 'Loading…');
  if (!background || !hasData) showOverlay('loading', 'Loading telemetry…');

  try {
    const result = await runner.run((signal) => fetchSeries(sel.device, sel.metric, sel.range, { signal }));
    if (result.stale) return; // a newer selection owns the UI now

    const points = result.value;
    lastOkAt = new Date();
    setStatus('ok', `Last refresh OK at ${timeFmt.format(lastOkAt)}`);
    if (points.length === 0) {
      clearChart();
      showOverlay('empty', 'No telemetry data available for this device and time range');
    } else {
      hideOverlay();
      renderChart(points, sel);
      hasData = true;
      const last = points[points.length - 1];
      el.summary.textContent =
        `${points.length} points · latest ${formatValue(sel.metric, last.v)} at ${fullFmt.format(new Date(last.t))}`;
    }
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : `Unexpected error: ${e && e.message}`;
    const when = lastOkAt ? ` Last successful refresh: ${timeFmt.format(lastOkAt)}.` : '';
    setStatus('error', `Request failed at ${timeFmt.format(new Date())}`);
    clearChart();
    showOverlay('error', `${msg}${when} Press Refresh to retry.`);
  } finally {
    // Only the most recent load re-enables the button and re-arms the timer.
    if (mySeq === loadSeq) {
      el.refresh.disabled = false;
      scheduleAuto();
    }
  }
}


function scheduleAuto() {
  clearTimeout(autoTimer);
  const seconds = Number(el.auto.value);
  if (seconds > 0) autoTimer = setTimeout(() => load({ background: true }), seconds * 1000);
}

// Typing: debounce, no request per keystroke. Enter/Refresh: immediate, fresh data.
el.device.addEventListener('input', () => {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => load(), DEVICE_DEBOUNCE_MS);
});
el.form.addEventListener('submit', (ev) => {
  ev.preventDefault();
  load();
});
el.metric.addEventListener('change', () => load());
el.range.addEventListener('change', () => load());
el.auto.addEventListener('change', () => {
  // Only re-arm the timer; never start a request here, so no overlap with an in-flight one.
  if (!el.refresh.disabled) scheduleAuto();
});

window.addEventListener('pagehide', () => {
  clearTimeout(debounceTimer);
  clearTimeout(autoTimer);
  runner.cancel();
  if (chart) chart.destroy();
});

load();

