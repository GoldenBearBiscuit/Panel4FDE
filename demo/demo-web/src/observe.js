/**
 * ══════════════════════════════════════════════════════════════════
 *  observe-kit 前端采集 SDK（L1 + L2）
 * ══════════════════════════════════════════════════════════════════
 *
 * 接入成本：两行
 *    import { initObserve } from './observe.js';
 *    initObserve({ router });
 *
 * 采集三件事（全部自动，业务代码零埋点）：
 *   1. 路由切换  → PAGE_VIEW   （Navigation 观察）
 *   2. 用户点击  → CLICK       （document 捕获阶段，不用给每个组件挂监听）
 *   3. 请求发起  → API         （包装 window.fetch）
 *
 * ★ 两个必须守住的规则（见 specs/SCHEMA.md 第三节）：
 *   ① 每次请求必须新建 traceId。会话级复用会把多次调用糊成一次，
 *      导致 duration / status 无法区分——比节点重复更隐蔽。
 *   ② 只上报「原始值」（path / selector），归一化一律由后端做。
 *      否则前后端可能算出不同的 node_key，图上每个接口会变成两个节点。
 */

const CFG = {
  endpoint: '/observe/report',
  flushIntervalMs: 1200,
  maxBatch: 50,
  maxQueue: 500,
  debug: false,
};

const STORE_KEY = 'observe.sessionId';

// ── 会话 ────────────────────────────────────────────────────────
let sessionId = null;
try {
  sessionId = sessionStorage.getItem(STORE_KEY);
  if (!sessionId) {
    sessionId = uuid();
    sessionStorage.setItem(STORE_KEY, sessionId);
  }
} catch (e) {
  sessionId = uuid();
}

let stepNo = 0;
/** 当前用户操作的 traceId：使 CLICK 与其触发的 API 归属同一次调用 */
let currentTrace = null;
let currentStep = 0;
/** 上一个节点，用于生成 parent（建 NAVIGATE / PRECEDES 边） */
let lastNode = null;
let enabled = true;
let queue = [];
let timer = null;

// ── 工具 ────────────────────────────────────────────────────────

function uuid() {
  if (crypto && crypto.randomUUID) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function hex(n) {
  let s = '';
  for (let i = 0; i < n; i++) s += ((Math.random() * 16) | 0).toString(16);
  return s;
}

function newTraceId() {
  return hex(32);
}

/** W3C Trace Context: 00-<32hex traceId>-<16hex spanId>-<01> */
function traceparent(traceId) {
  return `00-${traceId}-${hex(16)}-01`;
}

function log(...a) {
  if (CFG.debug) console.log('%c[observe]', 'color:#0a0', ...a);
}

// ── 事件上报 ────────────────────────────────────────────────────

function push(ev) {
  if (!enabled) return;

  // 只在同一操作内维护 parent，避免跨操作的边乱连
  let parentKind = null;
  let parentValue = null;
  if (ev.parentKind !== undefined) {
    parentKind = ev.parentKind;
    parentValue = ev.parentValue;
  } else if (lastNode) {
    parentKind = lastNode.kind;
    parentValue = lastNode.value;
  }

  const full = {
    eventId: uuid(),
    sessionId,
    stepNo: ev.stepNo !== undefined ? ev.stepNo : currentStep,
    occurredAt: ev.occurredAt || Date.now(),
    traceId: ev.traceId || null,
    type: ev.type,
    label: ev.label || null,
    path: ev.path || null,
    selector: ev.selector || null,
    method: ev.method || null,
    durationMs: ev.durationMs != null ? ev.durationMs : null,
    status: ev.status || 'OK',
    errorMsg: ev.errorMsg || null,
    raw: ev.raw ? JSON.stringify(ev.raw) : null,
    parentKind,
    parentValue,
  };
  queue.push(full);
  if (queue.length > CFG.maxQueue) queue.shift();
  log('push', full.type, full.label || full.path || full.selector);
  return full;
}

function flush(useBeacon) {
  if (!queue.length) return;
  const batch = queue.splice(0, CFG.maxBatch);
  const body = JSON.stringify({ sessionId, events: batch });
  try {
    if (useBeacon && navigator.sendBeacon) {
      // ★ 页面卸载必须用 sendBeacon：fetch 在 unload 阶段会被浏览器掐断
      navigator.sendBeacon(CFG.endpoint, new Blob([body], { type: 'application/json' }));
    } else {
      fetch(CFG.endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
        keepalive: true,
      }).catch(() => {});
    }
  } catch (e) {
    /* 采集失败绝不影响业务 */
  }
  if (queue.length) flush(false);
}

function scheduleFlush() {
  if (timer) return;
  timer = setTimeout(() => {
    timer = null;
    flush(false);
  }, CFG.flushIntervalMs);
}

// ── selector：优先稳定标识，绝不使用 nth-child ───────────────────

function describe(el) {
  if (!el || el.nodeType !== 1) return null;
  if (el.dataset && el.dataset.observeId) return `[data-observe-id=${el.dataset.observeId}]`;
  if (el.id) return `#${el.id}`;
  if (el.getAttribute && el.getAttribute('name')) {
    return `${el.tagName.toLowerCase()}[name=${el.getAttribute('name')}]`;
  }
  // 兜底：tag + 稳定 class。过滤掉含数字/过长（哈希样式）的 class
  const cls = Array.from(el.classList || [])
    .filter((c) => c && c.length <= 24 && !/\d/.test(c))
    .slice(0, 2);
  return el.tagName.toLowerCase() + (cls.length ? '.' + cls.join('.') : '');
}

function labelOf(el) {
  const t = (el.innerText || el.value || el.getAttribute?.('aria-label') || '').trim();
  return t ? t.split('\n')[0].slice(0, 32) : el.tagName.toLowerCase();
}

// ── 三类采集 ────────────────────────────────────────────────────

/** ① 路由切换 → PAGE_VIEW */
function hookRouter(router) {
  if (!router || !router.afterEach) return;
  router.afterEach((to) => {
    pageView(to);
  });
  // ★ 首屏：afterEach 不会为初始化时已经在的路由触发，需要手动补一条
  try {
    const cur = router.currentRoute && router.currentRoute.value;
    if (cur && cur.path && cur.path !== '/') pageView(cur);
  } catch (e) {
    /* 忽略 */
  }
}

function pageView(to) {
  const path = to.path || to.fullPath || '/';
  // ★ 每次交互新开一个 step + trace（见文件头规则①）
  stepNo += 1;
  currentStep = stepNo;
  currentTrace = null;

  push({
    type: 'PAGE_VIEW',
    path,
    label: to.meta && to.meta.title ? to.meta.title : path,
    occurredAt: Date.now(),
  });
  lastNode = { kind: 'PAGE', value: path };
  scheduleFlush();
}

/** ② 用户点击 → CLICK（document 捕获阶段，零组件侵入） */
function hookClicks() {
  document.addEventListener(
    'click',
    (e) => {
      try {
        let el = e.target;
        // 向上找到最近的可描述元素（按钮/链接优先）
        while (el && el.nodeType === 1 && !el.id && !el.dataset?.observeId &&
               !el.getAttribute?.('name') && !(el.tagName === 'BUTTON' || el.tagName === 'A')) {
          el = el.parentElement;
        }
        const sel = describe(el);
        if (!sel) return;

        stepNo += 1;
        currentStep = stepNo;
        // ★ 每次点击新开 trace：点击触发的请求会复用这一个
        currentTrace = newTraceId();

        push({
          type: 'CLICK',
          selector: sel,
          label: labelOf(el),
          stepNo: currentStep,
          traceId: currentTrace,
          occurredAt: Date.now(),
          raw: { tag: el.tagName, text: labelOf(el) },
        });
        lastNode = { kind: 'ACTION', value: sel };
        scheduleFlush();
      } catch (err) {
        /* 忽略 */
      }
    },
    true // ★ 捕获阶段：能在 document 上截到任意元素的点击
  );
}

/** ③ 请求 → API */
function hookFetch() {
  const orig = window.fetch;
  if (!orig) return;

  window.fetch = function (input, init) {
    let url = '';
    let method = 'GET';
    try {
      if (typeof input === 'string') {
        url = input;
        method = (init && init.method) || 'GET';
      } else if (input && input.url) {
        url = input.url;
        method = (init && init.method) || input.method || 'GET';
      }
    } catch (e) {
      /* 忽略 */
    }

    // 只观测自家接口，且不上报 /observe 自身（避免自我污染）
    const path = toPath(url);
    if (!path || path.startsWith('/observe')) {
      return orig.apply(this, arguments);
    }

    // ★ 规则①：每次请求新开 trace，绝不复用
    const traceId = currentTrace || newTraceId();
    const startedAt = Date.now();

    let headers;
    try {
      headers = new Headers(
        (init && init.headers) || (input instanceof Request ? input.headers : undefined)
      );
      headers.set('traceparent', traceparent(traceId));
      headers.set('X-Session-Id', sessionId);
      headers.set('X-Step-No', String(currentStep || 0));
    } catch (e) {
      headers = (init && init.headers) || undefined;
    }

    const nextInit = Object.assign({}, init, { headers });

    return orig.call(this, input, nextInit).then(
      (res) => {
        reportApi({ path, method, res, startedAt, traceId });
        return res;
      },
      (err) => {
        reportApi({ path, method, res: null, startedAt, traceId, error: err });
        throw err;
      }
    );
  };
}

function reportApi({ path, method, res, startedAt, traceId, error }) {
  const durationMs = Date.now() - startedAt;
  const ok = res && res.ok;
  push({
    type: 'API',
    path,
    method,
    stepNo: currentStep,
    traceId,
    // ★ occurredAt 用请求开始时刻，与后端 API 事件保持一致，保证排序正确
    occurredAt: startedAt,
    durationMs,
    status: ok ? 'OK' : 'ERROR',
    errorMsg: error ? String(error) : res && !res.ok ? `HTTP ${res.status}` : null,
    raw: { httpStatus: res ? res.status : null },
  });
  scheduleFlush();
}

function toPath(url) {
  if (!url) return null;
  try {
    const u = new URL(url, window.location.origin);
    if (u.origin !== window.location.origin) return null;
    return u.pathname;
  } catch (e) {
    return url.startsWith('/') ? url.split('?')[0] : null;
  }
}

// ── 初始化 ──────────────────────────────────────────────────────

export function initObserve(options) {
  const opts = options || {};
  Object.assign(CFG, opts.config || {});
  enabled = opts.enabled !== false;

  hookRouter(opts.router);
  hookClicks();
  hookFetch();

  // 页面隐藏时补一次 flush（sendBeacon 保证不丢）
  window.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flush(true);
  });
  window.addEventListener('pagehide', () => flush(true));

  const info = { sessionId, enabled, endpoint: CFG.endpoint };
  log('已初始化', info);
  if (CFG.debug) console.table(info);
  return info;
}

export function getSessionId() {
  return sessionId;
}

export function flushNow() {
  flush(false);
}
