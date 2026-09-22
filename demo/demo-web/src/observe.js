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
  /**
   * 不采集的页面路径（同源前缀匹配）。
   * ★ 观测控制台本身必须排除——否则“看图的动作”会污染被看的图，
   *   和你轮询一次就多出一批节点。与后端排除 /observe/** 同理。
   */
  ignorePaths: ['/graph', '/observe'],
  /**
   * 意图窗口：与最近一次用户交互相隔多久内的请求，算「人干的」。
   * 超过这个窗口的请求（组件挂载拉数据、轮询、预加载）算「页面自动干的」。
   * 两者在图上是不同的边：action→api = TRIGGER，page→api = AUTO。
   */
  intentWindowMs: 1200,
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

// ── 「人干的 vs 页面自动干的」判定状态 ──────────────────────
/** 最近一次用户交互的时间戳 */
let lastActionAt = 0;
/** 该交互对应的 action 节点 */
let lastActionNode = null;
/** 该交互是否已被某次请求「消费」（一次点击只归属一个请求） */
let lastActionUsed = false;
/** 当前所在页面节点，作为自动请求的 parent */
let currentPageNode = null;
let enabled = true;
let queue = [];
let timer = null;
/** ★ 幂等保护：HMR 会重新执行 main.js，或接入方可能调两次 initObserve，
 *  不加保护会重复注册监听器 → 一次点击产生 N 条事件（每条还各自新建 traceId，极难排查） */
let initialized = false;

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

/** 当前页面是否在排除名单里（观测控制台自身） */
function isIgnored() {
  let p = '';
  try {
    p = window.location.pathname || '';
  } catch (e) {
    return false;
  }
  return (CFG.ignorePaths || []).some((x) => p === x || p.indexOf(x + '/') === 0);
}

// ── 事件上报 ────────────────────────────────────────────────────

function push(ev) {
  if (!enabled || isIgnored()) return;

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

/**
 * 向上找最近的可描述元素。
 * ★ 碰到 body/html/#app 这类容器必须放弃，不能返回它们——
 *   否则所有“点击空白处”都会塌成同一个 `action:#app` 假节点，
 *   把图的噪声集中到一个无意义节点上。（已登记的修正项）
 */
const TOO_GENERIC = ['app', 'root', 'body', 'html', 'page', 'container', 'main'];

/** ★ 声明「这段 UI 不要采集」。任意元素加上 data-observe-ignore 属性即可，
 *  它整个子树内的点击都会被跳过。
 *  典型用途：观测面板自己的控制按钮——面板与业务同页时，不排除就会
 *  一边看图一边污染图（点一次「刷新」就多一个节点）。 */
const IGNORE_ATTR = 'data-observe-ignore';

function isIgnoredClick(startNode) {
  let n = startNode;
  while (n && n.nodeType === 1) {
    if (n.hasAttribute && n.hasAttribute(IGNORE_ATTR)) return true;
    n = n.parentElement;
  }
  return false;
}

function findTarget(start) {
  let el = start;
  let depth = 0;
  while (el && el.nodeType === 1 && depth < 8) {
    if (el.dataset?.observeId || el.getAttribute?.('name')) return el;
    if (el.tagName === 'BUTTON' || el.tagName === 'A' || el.tagName === 'SELECT') return el;
    if (el.id) {
      // id 太“泛”的容器不算可描述目标
      return TOO_GENERIC.includes(el.id.toLowerCase()) ? null : el;
    }
    el = el.parentElement;
    depth++;
  }
  return null;
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
  currentPageNode = { kind: 'PAGE', value: path };
  // ★ 导航已消费掉之前那次点击：新页面挂载时发的请求属于「页面自动干的」，
  //   不能归给“点了一下详情”那个 action（否则图上看不出来是自动拉的）
  lastActionUsed = true;
  scheduleFlush();
}

/** ② 用户点击 → CLICK（document 捕获阶段，零组件侵入） */
function hookClicks() {
  document.addEventListener(
    'click',
    (e) => {
      try {
        // ★ 被声明为不采集的区域（如观测面板自身）直接跳过
        if (isIgnoredClick(e.target)) return;

        const el = findTarget(e.target);
        if (!el) return; // 空白区域/泛容器：不记录，避免污染图
        const sel = describe(el);
        if (!sel) return;

        stepNo += 1;
        currentStep = stepNo;
        // ★ 每次点击新开 trace：点击触发的请求会复用这一个
        currentTrace = newTraceId();

        // ★ 开启意图窗口：紧随其后的请求算「人干的」
        lastActionAt = Date.now();
        lastActionNode = { kind: 'ACTION', value: sel };
        lastActionUsed = false;

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

  // ★ 「人干的」还是「页面自动干的」：
  //   人为 = 最近 intentWindowMs 内有过用户交互，且该交互还没被别的请求消费
  //   自动 = 其余全部（组件 onMounted 拉数据、轮询、预加载）
  //   两类在图上是不同的边（TRIGGER vs AUTO），不区分就等于让模型学噪声
  const byHuman =
    !!lastActionNode && !lastActionUsed && Date.now() - lastActionAt <= CFG.intentWindowMs;
  if (byHuman) lastActionUsed = true;
  const parent = byHuman ? lastActionNode : currentPageNode;

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
    raw: { httpStatus: res ? res.status : null, byHuman: byHuman },
    // 显式给出 parent：parentKind 为 null 表示“无人为归属”
    parentKind: parent ? parent.kind : null,
    parentValue: parent ? parent.value : null,
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
  if (initialized) {
    log('已初始化过，本次调用被忽略（幂等保护）');
    return { sessionId, enabled, endpoint: CFG.endpoint, alreadyInitialized: true };
  }
  initialized = true;

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
