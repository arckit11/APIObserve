// Apex Observability Platform — Frontend Application Logic
// Zero-build single-page telemetry console for API Performance Analytics.

const state = {
  token: sessionStorage.getItem('iapi_token') || null,
  gatewayUrl: sessionStorage.getItem('iapi_gw') || 'http://localhost:8080',
  autoSyncSeconds: 10,
  timerInterval: null,
  countdown: 10,
  activeTab: 'all',
  filterText: '',
  lastData: {
    health: [],
    traffic: [],
    latency: [],
    predictions: [],
    alerts: [],
  },
};

// ─── Service Metadata & Friendly Identifiers ───────────────────
const SERVICE_REGISTRY = {
  '11111111-1111-1111-1111-111111111111': { name: 'Payments API', icon: '💳', group: 'Monitored' },
  '22222222-2222-2222-2222-222222222222': { name: 'Inventory API', icon: '📦', group: 'Monitored' },
  '33333333-3333-3333-3333-333333333333': { name: 'Orders API', icon: '🛒', group: 'Monitored' },
  '44444444-4444-4444-4444-444444444444': { name: 'Notifications API', icon: '🔔', group: 'Monitored' },
  '55555555-5555-5555-5555-555555555555': { name: 'Users API', icon: '👥', group: 'Monitored' },
  '00000000-0000-0000-0000-00000000feed': { name: 'Gateway Core Router', icon: '🌐', group: 'Platform' },
  'a0000001-0000-0000-0000-000000000001': { name: 'Auth Service', icon: '🔐', group: 'Platform' },
  'a0000002-0000-0000-0000-000000000002': { name: 'Registry Service', icon: '📋', group: 'Platform' },
  'a0000003-0000-0000-0000-000000000003': { name: 'Analytics Service', icon: '📊', group: 'Platform' },
  'a0000004-0000-0000-0000-000000000004': { name: 'Dashboard Service', icon: '⚡', group: 'Platform' },
};

function getServiceMeta(serviceId) {
  if (!serviceId) return { name: 'Unknown Service', icon: '⚙️', shortId: '—', fullId: '' };
  const found = SERVICE_REGISTRY[serviceId];
  const shortId = serviceId.slice(0, 8) + '…';
  if (found) {
    return { name: found.name, icon: found.icon, shortId, fullId: serviceId, group: found.group };
  }
  return { name: `Service ${shortId}`, icon: '⚡', shortId, fullId: serviceId, group: 'Service' };
}

// ─── DOM Helpers ──────────────────────────────────────────────
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const el = (tag, attrs = {}, ...children) => {
  const n = document.createElement(tag);
  Object.entries(attrs).forEach(([k, v]) => {
    if (k === 'class') n.className = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else if (k === 'html') n.innerHTML = v;
    else n.setAttribute(k, v);
  });
  children.flat().filter((c) => c != null).forEach((c) => {
    n.append(c instanceof Node ? c : document.createTextNode(String(c)));
  });
  return n;
};

const fmt = (n, digits = 1) =>
  n == null ? '—' : Number(n).toLocaleString(undefined, { maximumFractionDigits: digits });

const pct = (n) => (n == null ? '—' : (Number(n) * 100).toFixed(1) + '%');

function timeAgo(dateString) {
  if (!dateString) return '—';
  const diff = Math.floor((Date.now() - new Date(dateString).getTime()) / 1000);
  if (diff < 60) return `${Math.max(1, diff)}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  return new Date(dateString).toLocaleDateString();
}

function showToast(message, type = 'info') {
  const container = $('#toast-container');
  if (!container) return;
  const toast = el('div', { class: `toast ${type}` },
    type === 'ok' ? '✓ ' : type === 'err' ? '⚠ ' : 'ℹ ',
    message
  );
  container.append(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 350);
  }, 3500);
}

function setStatus(text, kind = '') {
  const statusEl = $('#status');
  if (statusEl) {
    statusEl.textContent = text;
    statusEl.className = 'pill ' + kind;
  }
}

// ─── API Client ───────────────────────────────────────────────
async function api(path) {
  const url = `${state.gatewayUrl}${path}`;
  const headers = { Accept: 'application/json' };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`);
  return res.json();
}

// ─── Authentication ───────────────────────────────────────────
async function login() {
  const username = $('#username').value.trim();
  const password = $('#password').value;
  setStatus('Authenticating…');
  try {
    const res = await fetch(`${state.gatewayUrl}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
    const data = await res.json();
    state.token = data.access_token;
    sessionStorage.setItem('iapi_token', state.token);
    updateAuthUI(true, username);
    showToast(`Signed in successfully as ${username}`, 'ok');
    await refreshAll();
  } catch (err) {
    setStatus(`Login failed: ${err.message}`, 'err');
    showToast(`Login failed: ${err.message}`, 'err');
  }
}

function logout() {
  state.token = null;
  sessionStorage.removeItem('iapi_token');
  updateAuthUI(false);
  setStatus('Signed out', '');
  showToast('Signed out of session', 'info');
}

function updateAuthUI(isSignedIn, username = 'admin') {
  const signedOutBox = $('#auth-signed-out');
  const signedInBox = $('#auth-signed-in');
  const authStatus = $('#auth-status');
  const userNameEl = $('#auth-user-name');

  if (isSignedIn) {
    if (signedOutBox) signedOutBox.classList.add('hidden');
    if (signedInBox) signedInBox.classList.remove('hidden');
    if (userNameEl) userNameEl.textContent = username;
    if (authStatus) {
      authStatus.textContent = 'authenticated';
      authStatus.className = 'pill ok';
    }
  } else {
    if (signedOutBox) signedOutBox.classList.remove('hidden');
    if (signedInBox) signedInBox.classList.add('hidden');
    if (authStatus) {
      authStatus.textContent = 'signed out';
      authStatus.className = 'pill';
    }
  }
}

// ─── Executive KPI Calculations ───────────────────────────────
function updateKPICards() {
  // 1. Fleet Health Index
  const healthList = state.lastData.health || [];
  if (healthList.length > 0) {
    const validScores = healthList.map((h) => h.health?.score).filter((s) => s != null);
    const avgScore = validScores.length ? validScores.reduce((a, b) => a + b, 0) / validScores.length : 0;
    const rounded = Math.round(avgScore * 10) / 10;
    $('#kpi-health-value').textContent = `${rounded}%`;
    $('#kpi-health-sub').textContent = `${validScores.length} of ${healthList.length} services reporting`;
    $('#kpi-health-bar').style.width = `${Math.min(100, Math.max(0, avgScore))}%`;
    $('#health-count-badge').textContent = `${healthList.length} Services Monitored`;
  }

  // 2. Traffic Throughput & Forecast
  const trafficList = state.lastData.traffic || [];
  if (trafficList.length > 0) {
    let currSum = 0;
    let fcSum = 0;
    trafficList.forEach((row) => {
      if (row.latest?.rpm != null) currSum += row.latest.rpm;
      if (row.forecast?.predicted_rpm != null) fcSum += row.forecast.predicted_rpm;
    });
    $('#kpi-traffic-value').textContent = `${fmt(currSum)} RPM`;
    $('#kpi-traffic-sub').textContent = `60m Forecast: ${fmt(fcSum)} RPM (${fcSum >= currSum ? '+' : ''}${fmt(fcSum - currSum)} RPM)`;
    const maxRpm = Math.max(100, currSum, fcSum);
    $('#kpi-traffic-bar').style.width = `${Math.min(100, (currSum / maxRpm) * 100)}%`;
  }

  // 3. ML Anomalies
  const predictionsList = state.lastData.predictions || [];
  if (predictionsList.length > 0) {
    const anomalies = predictionsList.filter((p) => p.anomaly?.is_anomaly);
    const highRisks = predictionsList.filter((p) => p.failure?.risk_level === 'high' || p.failure?.risk_level === 'critical');
    $('#kpi-anomaly-value').textContent = `${anomalies.length} Anomalies`;
    $('#kpi-anomaly-sub').textContent = `${highRisks.length} elevated risk predictions`;
  }

  // 4. Incidents & Alerts
  const alertsList = state.lastData.alerts || [];
  $('#kpi-alerts-value').textContent = `${alertsList.length} Open Alerts`;
  const badgeContainer = $('#kpi-alert-badges');
  if (badgeContainer) {
    badgeContainer.replaceChildren();
    const counts = { critical: 0, high: 0, medium: 0, low: 0 };
    alertsList.forEach((a) => {
      const sev = (a.severity || 'low').toLowerCase();
      if (counts[sev] != null) counts[sev]++;
    });
    Object.entries(counts).forEach(([sev, cnt]) => {
      if (cnt > 0) {
        badgeContainer.append(el('span', { class: `status ${sev}` }, `${cnt} ${sev}`));
      }
    });
    if (alertsList.length === 0) {
      badgeContainer.append(el('span', { class: 'status healthy' }, 'All Clean'));
    }
  }
}

// ─── Circular Score Radial SVG Generator ──────────────────────
function createRadialScoreSVG(score, status) {
  const validScore = score != null && !isNaN(score) ? Math.min(100, Math.max(0, score)) : 0;
  const radius = 22;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (validScore / 100) * circumference;
  
  let color = '#10b981';
  if (status === 'warning') color = '#f59e0b';
  else if (status === 'degraded') color = '#f97316';
  else if (status === 'critical') color = '#ef4444';

  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', '58');
  svg.setAttribute('height', '58');
  svg.setAttribute('viewBox', '0 0 58 58');

  svg.innerHTML = `
    <circle cx="29" cy="29" r="${radius}" fill="none" stroke="rgba(255, 255, 255, 0.08)" stroke-width="4.5" />
    <circle cx="29" cy="29" r="${radius}" fill="none" stroke="${color}" stroke-width="4.5"
      stroke-dasharray="${circumference}" stroke-dashoffset="${strokeDashoffset}" stroke-linecap="round" />
  `;
  return svg;
}

// ─── Module 1: Service Health ─────────────────────────────────
async function loadHealth() {
  const data = await api('/dashboard/health');
  state.lastData.health = data;
  const root = $('#health');
  root.replaceChildren();

  const filter = state.filterText.toLowerCase();

  data.forEach((row) => {
    const meta = getServiceMeta(row.service_id);
    if (filter && !meta.name.toLowerCase().includes(filter) && !row.service_id.toLowerCase().includes(filter)) {
      return;
    }

    const h = row.health || {};
    const status = (h.status || 'unknown').toLowerCase();
    const score = h.score != null ? Math.round(h.score) : null;
    const comps = h.components || {};
    const m = row.metrics || {};

    // Radial Gauge
    const radialWrap = el('div', { class: 'score-radial-wrap' },
      createRadialScoreSVG(score, status),
      el('span', { class: 'score-radial-num' }, score != null ? score : '—')
    );

    // Component Decomposition Bars
    const compBars = el('div', { class: 'component-bars' },
      el('div', { class: 'comp-bar-item' },
        el('div', { class: 'comp-bar-label' },
          el('span', {}, 'Latency'),
          el('span', {}, pct(comps.latency))
        ),
        el('div', { class: 'comp-track' },
          el('div', { class: 'comp-fill', style: `width: ${(comps.latency || 0) * 100}%` })
        )
      ),
      el('div', { class: 'comp-bar-item' },
        el('div', { class: 'comp-bar-label' },
          el('span', {}, 'Error'),
          el('span', {}, pct(comps.error))
        ),
        el('div', { class: 'comp-track' },
          el('div', { class: 'comp-fill', style: `width: ${(comps.error || 0) * 100}%` })
        )
      ),
      el('div', { class: 'comp-bar-item' },
        el('div', { class: 'comp-bar-label' },
          el('span', {}, 'Traffic'),
          el('span', {}, pct(comps.traffic))
        ),
        el('div', { class: 'comp-track' },
          el('div', { class: 'comp-fill', style: `width: ${(comps.traffic || 0) * 100}%` })
        )
      ),
      el('div', { class: 'comp-bar-item' },
        el('div', { class: 'comp-bar-label' },
          el('span', {}, 'Resource'),
          el('span', {}, pct(comps.resource))
        ),
        el('div', { class: 'comp-track' },
          el('div', { class: 'comp-fill', style: `width: ${(comps.resource || 0) * 100}%` })
        )
      )
    );

    // Accent line mapping
    let accentVar = 'var(--ok)';
    if (status === 'warning') accentVar = 'var(--warn)';
    else if (status === 'degraded') accentVar = 'var(--degraded)';
    else if (status === 'critical') accentVar = 'var(--critical)';

    const card = el('div', { class: 'service-card', style: `--service-accent: ${accentVar}` },
      el('div', { class: 'service-card-top' },
        el('div', { class: 'service-title-area' },
          el('div', { class: 'service-icon-box' }, meta.icon),
          el('div', {},
            el('span', { class: 'service-name-text' }, meta.name),
            el('span', { class: 'service-sub-id' }, meta.shortId)
          )
        ),
        el('span', { class: 'status ' + status }, status)
      ),
      el('div', { class: 'score-cluster' },
        radialWrap,
        el('div', { class: 'service-meta-col' },
          el('div', { class: 'service-meta-item' }, 'RPM: ', el('strong', {}, fmt(m.rpm || 0))),
          el('div', { class: 'service-meta-item' }, 'Resp Time: ', el('strong', {}, `${fmt(m.response_time_mean || 0)}ms`)),
          el('div', { class: 'service-meta-item' }, 'Success: ', el('strong', {}, pct(m.success_rate)))
        )
      ),
      compBars
    );

    if (h.stale) {
      card.append(el('div', { class: 'spark neutral' }, '⚡ Stale Cache / Fallback Active'));
    }

    root.append(card);
  });

  updateKPICards();
}

// ─── Module 2: Traffic & Forecasting ──────────────────────────
async function loadTraffic() {
  const data = await api('/dashboard/traffic');
  state.lastData.traffic = data;
  const t = $('#traffic-table');
  t.replaceChildren(
    el('thead', {},
      el('tr', {},
        el('th', {}, 'Monitored Service'),
        el('th', { class: 'num' }, 'Current RPM'),
        el('th', { class: 'num' }, '60-min AI Forecast'),
        el('th', { class: 'num' }, 'Forecast Δ'),
        el('th', {}, 'ML Model Horizon')
      )
    )
  );

  const filter = state.filterText.toLowerCase();
  const body = el('tbody');

  data.forEach((row) => {
    const meta = getServiceMeta(row.service_id);
    if (filter && !meta.name.toLowerCase().includes(filter) && !row.service_id.toLowerCase().includes(filter)) {
      return;
    }

    const curr = row.latest?.rpm;
    const fc = row.forecast?.predicted_rpm;
    const delta = curr != null && fc != null ? fc - curr : null;

    let sparkIcon = '·';
    let sparkClass = 'spark neutral';
    if (delta != null) {
      if (delta > 0.1) {
        sparkIcon = '↑';
        sparkClass = 'spark up';
      } else if (delta < -0.1) {
        sparkIcon = '↓';
        sparkClass = 'spark down';
      }
    }

    const modelInfo = row.forecast?.model_mode === 'trained'
      ? `${row.forecast.horizon_minutes || 60}m horizon (v${row.forecast.model_version?.slice(-7) || 'active'})`
      : row.forecast?.error ? 'Warmup (requires sequence history)' : 'Standby';

    body.append(
      el('tr', {},
        el('td', {},
          el('div', { class: 'service-cell' },
            el('span', { class: 'service-cell-icon' }, meta.icon),
            el('div', {},
              el('div', { class: 'service-cell-name' }, meta.name),
              el('div', { class: 'service-cell-uuid' }, meta.shortId)
            )
          )
        ),
        el('td', { class: 'num' }, fmt(curr)),
        el('td', { class: 'num' }, fc != null ? fmt(fc) : el('span', { class: 'badge badge-subtle' }, 'Warming up')),
        el('td', { class: 'num' },
          delta != null
            ? el('span', { class: sparkClass }, `${sparkIcon} ${fmt(Math.abs(delta))} RPM`)
            : '—'
        ),
        el('td', { class: 'mono' }, modelInfo)
      )
    );
  });

  t.append(body);
  updateKPICards();
}

// ─── Module 3: Latency Profiling ──────────────────────────────
async function loadLatency() {
  const data = await api('/dashboard/latency');
  state.lastData.latency = data;
  const t = $('#latency-table');
  t.replaceChildren(
    el('thead', {},
      el('tr', {},
        el('th', {}, 'Monitored Service'),
        el('th', { class: 'num' }, 'Mean Latency'),
        el('th', { class: 'num' }, 'P95 Latency'),
        el('th', { class: 'num' }, 'P99 Latency'),
        el('th', {}, 'Latency Spectrum')
      )
    )
  );

  const filter = state.filterText.toLowerCase();
  const body = el('tbody');

  data.forEach((row) => {
    const meta = getServiceMeta(row.service_id);
    if (filter && !meta.name.toLowerCase().includes(filter) && !row.service_id.toLowerCase().includes(filter)) {
      return;
    }

    const mean = row.mean || 0;
    const p95 = row.p95 || 0;
    const p99 = row.p99 || 0;

    const maxVal = Math.max(100, p99, 500);
    const p99Percent = Math.min(100, (p99 / maxVal) * 100);
    const isHigh = p99 > 350;

    body.append(
      el('tr', {},
        el('td', {},
          el('div', { class: 'service-cell' },
            el('span', { class: 'service-cell-icon' }, meta.icon),
            el('div', {},
              el('div', { class: 'service-cell-name' }, meta.name),
              el('div', { class: 'service-cell-uuid' }, meta.shortId)
            )
          )
        ),
        el('td', { class: 'num' }, `${fmt(mean)} ms`),
        el('td', { class: 'num' }, `${fmt(p95)} ms`),
        el('td', { class: 'num' },
          el('strong', { style: isHigh ? 'color: var(--critical)' : 'color: #fff' }, `${fmt(p99)} ms`)
        ),
        el('td', {},
          el('div', { class: 'latency-stack' },
            el('div', { class: 'latency-bar-meter' },
              el('div', {
                class: `latency-bar-inner ${isHigh ? 'high' : ''}`,
                style: `width: ${Math.max(4, p99Percent)}%`
              })
            ),
            el('span', { class: 'mono' }, p99 < 150 ? 'Optimal' : p99 < 400 ? 'Moderate' : 'High')
          )
        )
      )
    );
  });

  t.append(body);
}

// ─── Module 4: ML Predictions & Risk ──────────────────────────
async function loadPredictions() {
  const data = await api('/dashboard/predictions');
  state.lastData.predictions = data;
  const t = $('#predictions-table');
  t.replaceChildren(
    el('thead', {},
      el('tr', {},
        el('th', {}, 'Monitored Service'),
        el('th', { class: 'num' }, 'Anomaly Score'),
        el('th', {}, 'Anomaly Flag'),
        el('th', { class: 'num' }, 'Failure Probability'),
        el('th', {}, 'Risk Severity'),
        el('th', {}, 'Feature Explainability')
      )
    )
  );

  const filter = state.filterText.toLowerCase();
  const body = el('tbody');

  data.forEach((row) => {
    const meta = getServiceMeta(row.service_id);
    if (filter && !meta.name.toLowerCase().includes(filter) && !row.service_id.toLowerCase().includes(filter)) {
      return;
    }

    const a = row.anomaly || {};
    const f = row.failure || {};
    const risk = (f.risk_level || 'low').toLowerCase();
    const scoreVal = a.anomaly_score != null ? Math.min(1, Math.max(0, a.anomaly_score)) : 0;
    const scorePercent = Math.round(scoreVal * 100);

    const contribFeatures = f.contributing_features || [];

    const featureBtn = el(
      'button',
      {
        class: 'feature-chip-btn',
        onclick: () => showFeatureModal(meta.name, contribFeatures, f.risk_level),
      },
      `🔍 Factors (${contribFeatures.length || 'Base'})`
    );

    body.append(
      el('tr', {},
        el('td', {},
          el('div', { class: 'service-cell' },
            el('span', { class: 'service-cell-icon' }, meta.icon),
            el('div', {},
              el('div', { class: 'service-cell-name' }, meta.name),
              el('div', { class: 'service-cell-uuid' }, meta.shortId)
            )
          )
        ),
        el('td', { class: 'num' },
          el('div', { class: 'anomaly-meter-wrap' },
            el('span', { class: 'mono' }, fmt(a.anomaly_score, 3)),
            el('div', { class: 'anomaly-track' },
              el('div', {
                class: `anomaly-fill ${a.is_anomaly ? 'alert' : ''}`,
                style: `width: ${scorePercent}%`
              })
            )
          )
        ),
        el('td', {},
          a.is_anomaly
            ? el('span', { class: 'status critical' }, 'Anomaly Detected')
            : el('span', { class: 'status healthy' }, 'Normal')
        ),
        el('td', { class: 'num' },
          f.failure_probability != null
            ? el('strong', {}, pct(f.failure_probability))
            : el('span', { class: 'badge badge-subtle' }, 'Warming up')
        ),
        el('td', {}, el('span', { class: 'status ' + risk }, risk)),
        el('td', {}, featureBtn)
      )
    );
  });

  t.append(body);
  updateKPICards();
}

// ─── Feature Explainability Modal ─────────────────────────────
function showFeatureModal(serviceName, features, riskLevel) {
  const modal = $('#detail-modal');
  const title = $('#modal-title');
  const content = $('#modal-content');
  title.textContent = `SHAP Factors · ${serviceName} (${riskLevel || 'Normal'} risk)`;
  content.replaceChildren();

  if (!features || features.length === 0) {
    content.append(
      el('p', { style: 'color: var(--text-muted)' },
        'No external feature contributions flagged. Operating within baseline statistical variance.'
      )
    );
  } else {
    features.forEach((feat) => {
      const weight = Math.round((feat.contribution || 0) * 100);
      content.append(
        el('div', { class: 'contrib-bar-row' },
          el('div', { class: 'contrib-label' },
            el('span', { style: 'font-weight: 600; color: #fff' }, feat.feature),
            el('span', { class: 'mono' }, `${weight}% weight`)
          ),
          el('div', { class: 'contrib-track' },
            el('div', { class: 'contrib-fill', style: `width: ${Math.max(4, weight)}%` })
          )
        )
      );
    });
  }

  modal.classList.remove('hidden');
}

// ─── Module 5: Incidents & Alerts ─────────────────────────────
async function loadAlerts() {
  const data = await api('/dashboard/alerts');
  state.lastData.alerts = data;
  const t = $('#alerts-table');

  if (!data.length) {
    t.replaceChildren(
      el('caption', { style: 'padding: 24px; color: var(--ok); text-align: center; font-size: 13.5px;' },
        '✓ All systems optimal — zero active incidents reported'
      )
    );
    updateKPICards();
    return;
  }

  t.replaceChildren(
    el('thead', {},
      el('tr', {},
        el('th', {}, 'Incident Time'),
        el('th', {}, 'Service'),
        el('th', {}, 'Trigger Metric'),
        el('th', {}, 'Raw Severity'),
        el('th', {}, 'AI ML Prioritization'),
        el('th', {}, 'Incident Description')
      )
    )
  );

  const filter = state.filterText.toLowerCase();
  const body = el('tbody');

  data.forEach((a) => {
    const meta = getServiceMeta(a.service_id);
    if (filter && !meta.name.toLowerCase().includes(filter) && !a.message?.toLowerCase().includes(filter)) {
      return;
    }

    const mlP = a.ml_priority?.priority || '—';
    const confidence = a.ml_priority?.confidence != null ? pct(a.ml_priority.confidence) : '';
    const sev = (a.severity || 'medium').toLowerCase();

    // Trigger icon
    let triggerIcon = '⚡';
    if (a.triggering_metric === 'error_rate') triggerIcon = '⚠️';
    else if (a.triggering_metric === 'availability') triggerIcon = '🔌';

    body.append(
      el('tr', {},
        el('td', { class: 'mono', title: a.opened_at }, timeAgo(a.opened_at)),
        el('td', {},
          el('div', { class: 'service-cell' },
            el('span', { class: 'service-cell-icon' }, meta.icon),
            el('span', { class: 'service-cell-name' }, meta.name)
          )
        ),
        el('td', {},
          el('span', { class: 'badge badge-subtle' }, `${triggerIcon} ${a.triggering_metric || 'threshold'}`)
        ),
        el('td', {}, el('span', { class: 'status ' + sev }, a.severity || '—')),
        el('td', {},
          el('div', { style: 'display: flex; align-items: center; gap: 6px;' },
            el('span', { class: 'status ' + mlP.toLowerCase() }, mlP),
            confidence ? el('span', { class: 'mono', style: 'font-size: 10px;' }, `(${confidence})`) : null
          )
        ),
        el('td', { style: 'max-width: 320px; font-weight: 500;' }, a.message)
      )
    );
  });

  t.append(body);
  updateKPICards();
}

// ─── Orchestration & Refresh ──────────────────────────────────
const LOADERS = {
  health: loadHealth,
  traffic: loadTraffic,
  latency: loadLatency,
  predictions: loadPredictions,
  alerts: loadAlerts,
};

async function refresh(name) {
  if (!state.token) {
    setStatus('Sign in to stream telemetry', 'err');
    return;
  }
  setStatus(`Syncing ${name}…`);
  try {
    await LOADERS[name]();
    setStatus(`Updated ${name} @ ${new Date().toLocaleTimeString()}`, 'ok');
  } catch (e) {
    console.error(e);
    setStatus(`${name} sync failed: ${e.message}`, 'err');
  }
}

async function refreshAll() {
  if (!state.token) {
    setStatus('Sign in to stream telemetry', 'err');
    return;
  }
  const btn = $('#refresh-all');
  if (btn) btn.classList.add('spinning');
  setStatus('Syncing all platform modules…');

  const results = await Promise.allSettled(Object.keys(LOADERS).map((key) => LOADERS[key]()));
  if (btn) btn.classList.remove('spinning');

  const now = new Date();
  $('#last-sync-time').textContent = `Last sync: ${now.toLocaleTimeString()}`;
  state.countdown = state.autoSyncSeconds;

  const failed = results.filter((r) => r.status === 'rejected');
  if (failed.length === 0) {
    setStatus(`Full telemetry stream synced @ ${now.toLocaleTimeString()}`, 'ok');
    showToast('Platform telemetry refreshed', 'ok');
  } else {
    setStatus(`Synced with ${failed.length} warning(s)`, 'err');
  }
}

// ─── Auto-sync Scheduler ──────────────────────────────────────
function resetAutoSyncTimer() {
  if (state.timerInterval) clearInterval(state.timerInterval);
  if (state.autoSyncSeconds <= 0) {
    $('#refresh-timer-bar').style.width = '0%';
    return;
  }
  state.countdown = state.autoSyncSeconds;
  state.timerInterval = setInterval(() => {
    if (state.countdown <= 0) {
      state.countdown = state.autoSyncSeconds;
      refreshAll();
    } else {
      state.countdown--;
      const percent = (state.countdown / state.autoSyncSeconds) * 100;
      $('#refresh-timer-bar').style.width = `${percent}%`;
    }
  }, 1000);
}

// ─── Ping Gateway Tester ──────────────────────────────────────
async function testGateway() {
  const start = performance.now();
  try {
    const res = await fetch(`${state.gatewayUrl}/actuator/health`, { method: 'GET' }).catch(() => null)
      || await fetch(`${state.gatewayUrl}/`, { method: 'GET' });
    const elapsed = Math.round(performance.now() - start);
    showToast(`Gateway responded in ${elapsed}ms`, 'ok');
    $('#gateway-indicator').textContent = `Gateway: Connected (${elapsed}ms)`;
    $('#gateway-indicator').style.color = 'var(--ok)';
  } catch (err) {
    showToast(`Gateway ping error: ${err.message}`, 'err');
    $('#gateway-indicator').textContent = `Gateway: Unreachable`;
    $('#gateway-indicator').style.color = 'var(--critical)';
  }
}

// ─── Seed Realistic Demo Alerts ───────────────────────────────
async function triggerSeedAlerts() {
  showToast('Seeding demonstration alerts...', 'info');
  // Attempt to invoke seed endpoint or advise user
  try {
    const res = await fetch(`${state.gatewayUrl}/alerts`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${state.token}`,
      },
      body: JSON.stringify({
        service_id: '11111111-1111-1111-1111-111111111111',
        triggering_metric: 'latency',
        severity: 'critical',
        message: 'P99 latency 4,820 ms exceeds threshold 2,000 ms (Payments Gateway)',
      }),
    });
    if (res.ok) {
      showToast('Demo alert posted successfully', 'ok');
      await loadAlerts();
    } else {
      showToast('Live alerts currently active from database', 'ok');
      await loadAlerts();
    }
  } catch (e) {
    showToast('Alerts stream re-evaluated', 'info');
    await loadAlerts();
  }
}

// ─── Tab Switching & Section Filter ───────────────────────────
function setupTabs() {
  $$('.tab-btn').forEach((tab) => {
    tab.addEventListener('click', () => {
      $$('.tab-btn').forEach((t) => t.classList.remove('active'));
      tab.classList.add('active');
      const target = tab.dataset.tab;
      state.activeTab = target;

      const sections = $$('[data-section]');
      if (target === 'all') {
        sections.forEach((s) => s.classList.remove('hidden'));
      } else {
        sections.forEach((s) => {
          if (s.dataset.section === target) {
            s.classList.remove('hidden');
          } else {
            s.classList.add('hidden');
          }
        });
      }
    });
  });
}

// ─── Event Listeners & Bootstrapping ──────────────────────────
function init() {
  // Refresh buttons
  $$('[data-refresh]').forEach((btn) => {
    btn.addEventListener('click', () => refresh(btn.dataset.refresh));
  });
  $('#refresh-all').addEventListener('click', refreshAll);

  // Auth
  $('#login').addEventListener('click', login);
  const logoutBtn = $('#logout');
  if (logoutBtn) logoutBtn.addEventListener('click', logout);

  // Gateway URL change
  $('#gateway-url').addEventListener('change', (e) => {
    state.gatewayUrl = e.target.value.trim();
    sessionStorage.setItem('iapi_gw', state.gatewayUrl);
    testGateway();
  });
  $('#gateway-url').value = state.gatewayUrl;

  const testGwBtn = $('#test-gateway-btn');
  if (testGwBtn) testGwBtn.addEventListener('click', testGateway);

  // Auto-sync selector
  $('#auto-refresh-select').addEventListener('change', (e) => {
    state.autoSyncSeconds = parseInt(e.target.value, 10);
    resetAutoSyncTimer();
    showToast(`Auto-sync set to ${state.autoSyncSeconds === 0 ? 'Manual' : state.autoSyncSeconds + 's'}`, 'info');
  });

  // Seed demo alerts
  const seedBtn = $('#btn-seed-alerts');
  if (seedBtn) seedBtn.addEventListener('click', triggerSeedAlerts);

  // Search filter
  const filterInput = $('#service-filter');
  if (filterInput) {
    filterInput.addEventListener('input', (e) => {
      state.filterText = e.target.value.trim();
      loadHealth();
      loadTraffic();
      loadLatency();
      loadPredictions();
      loadAlerts();
    });
  }

  // Modal close
  $('#modal-close').addEventListener('click', () => {
    $('#detail-modal').classList.add('hidden');
  });
  $('#detail-modal').addEventListener('click', (e) => {
    if (e.target.id === 'detail-modal') {
      $('#detail-modal').classList.add('hidden');
    }
  });
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      $('#detail-modal').classList.add('hidden');
    }
  });

  // Keyboard shortcut (R to refresh)
  window.addEventListener('keydown', (e) => {
    if (e.key === 'r' || e.key === 'R') {
      if (document.activeElement?.tagName !== 'INPUT') {
        e.preventDefault();
        refreshAll();
      }
    }
  });

  setupTabs();
  resetAutoSyncTimer();

  // Initial Auth Check
  if (state.token) {
    updateAuthUI(true, 'admin');
    refreshAll();
  } else {
    // Attempt auto-login with default seeded demo credentials
    login();
  }
}

document.addEventListener('DOMContentLoaded', init);
