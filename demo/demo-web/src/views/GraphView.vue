<template>
  <div class="card live">
    <!-- ── 控制条 ── -->
    <div class="row">
      <h2>实时观测台</h2>
      <select v-model="selected" @change="switchSession(selected)" style="width: 300px">
        <option value="">（最近会话）</option>
        <option v-for="s in sessions" :key="s.session_id" :value="s.session_id">
          {{ short(s.session_id) }} · {{ s.event_count }} 事件 · {{ s.node_count }} 节点
        </option>
      </select>
      <label class="chk"><input type="checkbox" v-model="auto" /> 自动刷新</label>
      <button id="btn-reload" @click="reload">刷新</button>
      <button id="btn-current" @click="useCurrent">看当前会话</button>
      <button id="btn-fit" @click="fitAll">适应画布</button>
      <span class="stat" :title="statsTip">
        事件 <b>{{ events.length }}</b> · 节点 <b>{{ d.nodeCount || 0 }}</b> · 边 <b>{{ d.edgeCount || 0 }}</b>
        <em v-if="dropped" class="warn">丢弃 {{ dropped }}</em>
      </span>
    </div>

    <div v-if="error" class="err">{{ error }}</div>

    <!-- ── 两栏 ── -->
    <div class="cols">
      <!-- 左：实时事件流 -->
      <section class="stream">
        <div class="panel-head">
          实时记录
          <span v-if="newCount" class="badge">{{ newCount }} 条新</span>
          <span class="hint-inline">（另一标签页操作，这里会实时长出来）</span>
        </div>
        <div ref="listEl" class="stream-list" @scroll="onScroll">
          <div v-if="!events.length" class="empty">
            还没有事件。<br />去另一个标签页打开 <b>订单列表</b> 点几下，这里会实时冒出来。
          </div>
          <div
            v-for="e in events"
            :key="e.id"
            class="ev"
            :class="[e._new ? 'is-new' : '', e.status === 'ERROR' ? 'is-err' : '']"
            :title="e.node_key + (e.raw_payload ? '\n' + e.raw_payload : '')"
          >
            <span class="t">{{ hms(e.occurred_at) }}</span>
            <i class="dot" :style="{ background: layerOf(e.layer).c }"></i>
            <span class="ly" :style="{ color: layerOf(e.layer).c }">{{ layerOf(e.layer).t }}</span>
            <span class="et">{{ e.event_type }}</span>
            <span class="lb">{{ e.node_label || e.node_key }}</span>
            <span v-if="e.edge_type && e.edge_type !== 'PRECEDES'" class="edge">←{{ e.edge_type }}</span>
            <span v-if="e.duration_ms != null" class="ms">{{ e.duration_ms }}ms</span>
            <span v-if="e.status === 'ERROR'" class="err-tag">ERROR</span>
          </div>
        </div>
      </section>

      <!-- 右：图 -->
      <section class="graphbox">
        <div class="panel-head">
          操作路线图
          <span class="legend">
            <i style="background: #5b8ff9"></i>前端
            <i style="background: #f6bd16"></i>后端
            <i style="background: #5ad8a6"></i>资源层
            <i class="dash"></i>虚线=PRECEDES
          </span>
          <span class="hint-inline">可拖拽平移、滚轮缩放</span>
        </div>
        <div ref="el" class="canvas"></div>
        <div v-if="!d.nodeCount" class="empty">还没有节点。</div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import G6 from '@antv/g6';
import { getSessionId } from '../observe.js';

const el = ref(null);
const listEl = ref(null);
const sessions = ref([]);
const selected = ref('');
const events = ref([]);
const d = ref({});
const error = ref(null);
const auto = ref(true);
const newCount = ref(0);
const dropped = ref(0);
const statsTip = ref('');

let graph = null;
let timer = null;
let lastId = 0;
let lastSig = '';
let stuckToBottom = true;
let flashTimers = [];

const COLOR = { FRONTEND: '#5b8ff9', BACKEND: '#f6bd16', RESOURCE: '#5ad8a6' };
const LABEL = { FRONTEND: '前端', BACKEND: '后端', RESOURCE: '资源' };
const SHAPE = { FRONTEND: 'circle', BACKEND: 'rect', RESOURCE: 'diamond' };

const layerOf = (l) => ({ c: COLOR[l] || '#999', t: LABEL[l] || l || '?' });
const short = (s) => (s ? String(s).slice(0, 8) : '-');

function hms(v) {
  if (v == null) return '';
  const d2 = new Date(v);
  if (isNaN(d2.getTime())) return String(v).slice(11, 23);
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${p(d2.getHours())}:${p(d2.getMinutes())}:${p(d2.getSeconds())}.${p(d2.getMilliseconds(), 3)}`;
}

// ── 事件流 ─────────────────────────────────────────────────────

async function pullEvents() {
  if (!selected.value) return;
  try {
    const res = await fetch(
      `/observe/events?sessionId=${encodeURIComponent(selected.value)}&afterId=${lastId}&limit=300`
    );
    const j = await res.json();
    if (!j.events || !j.events.length) return;

    for (const e of j.events) e._new = true;
    events.value = events.value.concat(j.events);
    lastId = j.lastId || lastId;
    newCount.value += j.events.length;

    // 1.5 秒后取消高亮
    const ids = j.events.map((e) => e.id);
    flashTimers.push(
      setTimeout(() => {
        for (const e of events.value) if (ids.includes(e.id)) e._new = false;
      }, 1500)
    );

    await nextTick();
    if (stuckToBottom && listEl.value) {
      listEl.value.scrollTop = listEl.value.scrollHeight;
    }
  } catch (e) {
    /* 忽略，下一轮再试 */
  }
}

function onScroll() {
  const n = listEl.value;
  if (!n) return;
  stuckToBottom = n.scrollHeight - n.scrollTop - n.clientHeight < 30;
  if (stuckToBottom) newCount.value = 0;
}

// ── 图 ────────────────────────────────────────────────────────

function truncate(s, n) {
  s = String(s == null ? '' : s);
  return s.length > n ? s.slice(0, n) + '…' : s;
}

async function pullGraph(force) {
  if (!selected.value) return;
  try {
    const res = await fetch(`/observe/graph?sessionId=${encodeURIComponent(selected.value)}`);
    const g = await res.json();
    const sig = `${g.eventCount}/${g.nodeCount}/${g.edgeCount}`;
    d.value = g;
    if (!force && sig === lastSig) return; // ★ 数据没变就不重绘，避免每秒重排图
    lastSig = sig;
    await nextTick();
    render();
  } catch (e) {
    error.value = '拉取图数据失败: ' + e;
  }
}

async function pullStats() {
  try {
    const s = await (await fetch('/observe/stats')).json();
    dropped.value = s.dropped || 0;
    statsTip.value = `接收 ${s.received} / 落库 ${s.written} / 丢弃 ${s.dropped} / 失败 ${s.writeErrors} / 队列中 ${s.pending}`;
  } catch (e) {
    /* 忽略 */
  }
}

// ── 渲染（与阶段一逻辑一致：原尺寸 + 纵向 + 页面滚动） ──────────

const MIN_CANVAS_H = 420;
const MAX_CANVAS_H = 3200;
let fitting = false;

function contentBounds() {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  graph.getNodes().forEach((nd) => {
    const m = nd.getModel();
    if (typeof m.x !== 'number' || typeof m.y !== 'number') return;
    minX = Math.min(minX, m.x - 80);
    maxX = Math.max(maxX, m.x + 80);
    minY = Math.min(minY, m.y - 30);
    maxY = Math.max(maxY, m.y + 46);
  });
  return isFinite(minX) ? { minX, minY, maxX, maxY } : null;
}

function fitCanvasToContent() {
  if (!graph || fitting || !el.value) return;
  fitting = true;
  try {
    const b = contentBounds();
    if (!b) return;
    const w = el.value.clientWidth || 700;
    const needH = Math.ceil(b.maxY - b.minY) + 70;
    const h = Math.min(MAX_CANVAS_H, Math.max(MIN_CANVAS_H, needH));
    if (el.value.clientHeight !== h) {
      graph.changeSize(w, h);
      el.value.style.height = h + 'px';
    }
    graph.translate(70 - b.minX, 30 - b.minY);
    window.__graphBounds = b;

    // 验收脚本读这个对象做客观判定，不靠“看起来对”
    try {
      window.__graphInfo = {
        nodes: d.value ? d.value.nodes.length : null,
        edges: d.value ? d.value.edges.length : null,
        zoom: graph.getZoom(),
        canvasH: el.value.clientHeight,
        bounds: b,
        layout: 'ok',
      };
    } catch (e) {
      /* 忽略 */
    }
  } catch (e) {
    /* 忽略 */
  } finally {
    fitting = false;
  }
}

function render() {
  const data = d.value;
  if (!el.value) return;
  if (graph) {
    graph.destroy();
    graph = null;
  }
  if (!data || !data.nodes || !data.nodes.length) return;

  const nodes = data.nodes.map((n) => ({
    id: n.id,
    label: truncate(n.label, 22),
    type: SHAPE[n.layer] || 'rect',
    style: {
      fill: (COLOR[n.layer] || '#999') + '33',
      stroke: COLOR[n.layer] || '#999',
      lineWidth: 1.5,
    },
    labelCfg: { position: 'bottom', style: { fontSize: 12, fill: '#333' } },
  }));

  const edges = data.edges.map((e, i) => ({
    id: 'e' + i,
    source: e.source,
    target: e.target,
    edgeType: e.type,
    label: e.type === 'PRECEDES' ? '' : e.type,
    style:
      e.type === 'PRECEDES'
        ? { stroke: '#ccc', lineDash: [4, 4], endArrow: true }
        : { stroke: '#9aa4b2', endArrow: true },
    labelCfg: { style: { fontSize: 10, fill: '#7a8290' } },
  }));

  const makeCfg = (layout) => ({
    container: el.value,
    width: el.value.clientWidth || 700,
    height: MIN_CANVAS_H,
    fitView: false,
    zoom: 1,
    modes: { default: ['drag-canvas', 'zoom-canvas', 'drag-node'] },
    layout,
    defaultNode: { size: 38 },
    defaultEdge: { type: 'polyline' },
    animate: false,
  });

  try {
    graph = new G6.Graph(makeCfg({ type: 'dagre', rankdir: 'TB', nodesep: 26, ranksep: 46 }));
    graph.on('afterlayout', fitCanvasToContent);
    graph.data({ nodes, edges });
    graph.render();
    setTimeout(fitCanvasToContent, 350);
  } catch (e) {
    error.value = 'dagre 失败，降级 force：' + e.message;
    try {
      graph = new G6.Graph(makeCfg({ type: 'force', linkDistance: 160, preventOverlap: true }));
      graph.on('afterlayout', fitCanvasToContent);
      graph.data({ nodes, edges });
      graph.render();
      setTimeout(fitCanvasToContent, 350);
    } catch (e2) {
      error.value = '图渲染失败：' + e2.message;
    }
  }
}

function fitAll() {
  if (graph) {
    try {
      graph.fitView(28);
    } catch (e) {
      /* 忽略 */
    }
  }
}

// ── 会话与轮询 ────────────────────────────────────────────────

async function loadSessions() {
  try {
    sessions.value = await (await fetch('/observe/sessions?limit=20')).json();
  } catch (e) {
    /* 忽略 */
  }
}

async function switchSession(sid) {
  events.value = [];
  lastId = 0;
  lastSig = '';
  newCount.value = 0;
  error.value = null;
  selected.value = sid;
  if (graph) {
    graph.destroy();
    graph = null;
  }
  d.value = {};
  await pullEvents();
  await pullGraph(true);
}

async function useCurrent() {
  const sid = getSessionId();
  await switchSession(sid || '');
}

async function reload() {
  await switchSession(selected.value);
  await loadSessions();
}

async function tick() {
  if (auto.value) {
    await pullEvents();
    await pullGraph(false);
    await pullStats();
  }
  timer = setTimeout(tick, auto.value ? 1000 : 5000);
}

onMounted(async () => {
  await loadSessions();
  const latest = sessions.value.length ? String(sessions.value[0].session_id) : '';
  await switchSession(latest);
  tick();
});

onUnmounted(() => {
  if (timer) clearTimeout(timer);
  for (const t of flashTimers) clearTimeout(t);
  flashTimers = [];
  if (graph) graph.destroy();
  graph = null;
});
</script>

<style scoped>
.live { display: flex; flex-direction: column; height: calc(100vh - 96px); }

.row { flex-wrap: wrap; }
.row h2 { white-space: nowrap; }
.chk { display: flex; align-items: center; gap: 4px; font-size: 12px; color: #555; white-space: nowrap; }
.stat { margin-left: auto; font-size: 12px; color: #555; white-space: nowrap; }
.stat em.warn { color: #b33; font-style: normal; margin-left: 6px; }

.cols { display: flex; gap: 12px; flex: 1; min-height: 0; margin-top: 10px; }

/* 左栏 */
.stream { width: 430px; flex: none; display: flex; flex-direction: column; min-height: 0;
          border: 1px solid #eef0f2; border-radius: 4px; background: #fff; }
.stream-list { flex: 1; overflow: auto; padding: 4px 0; }
.ev { display: flex; align-items: baseline; gap: 6px; padding: 2px 8px; font-size: 11.5px;
      font-family: ui-monospace, Consolas, monospace; white-space: nowrap; line-height: 1.7;
      border-left: 3px solid transparent; transition: background 0.4s; }
.ev:hover { background: #f4f7fb; }
.ev.is-new { background: #eaf4ff; border-left-color: #5b8ff9; }
.ev.is-err { background: #fff3f3; }
.ev .t { color: #9aa4b2; flex: none; }
.ev .dot { width: 7px; height: 7px; border-radius: 50%; flex: none; align-self: center; }
.ev .ly { flex: none; width: 26px; }
.ev .et { flex: none; width: 70px; color: #666; }
.ev .lb { color: #222; overflow: hidden; text-overflow: ellipsis; }
.ev .edge { color: #b0b7c3; flex: none; }
.ev .ms { color: #9aa4b2; flex: none; }
.ev .err-tag { color: #b33; font-weight: 600; flex: none; }

/* 右栏 */
.graphbox { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0;
            border: 1px solid #eef0f2; border-radius: 4px; background: #fcfcfd; }
.graphbox .canvas { overflow: auto; flex: 1; min-height: 0; cursor: grab; }

.panel-head { display: flex; align-items: center; gap: 10px; padding: 6px 10px; font-size: 12px;
              font-weight: 600; color: #444; border-bottom: 1px solid #eef0f2; background: #fafbfc;
              border-radius: 4px 4px 0 0; }
.hint-inline { font-weight: 400; color: #9aa4b2; }
.badge { background: #5b8ff9; color: #fff; border-radius: 8px; padding: 0 6px; font-size: 10px; font-weight: 500; }
.legend { display: flex; align-items: center; gap: 4px; font-weight: 400; color: #666; font-size: 11px; }
.legend i { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-left: 6px; }
.legend i.dash { background: none; border-top: 2px dashed #ccc; height: 0; width: 14px; margin-top: 4px; }
.empty { padding: 24px; color: #9aa4b2; font-size: 12px; line-height: 1.9; text-align: center; }
.err { margin: 8px 0; padding: 8px 10px; background: #fff3f3; border: 1px solid #ffd0d0;
       color: #b33; font-size: 12px; border-radius: 4px; }
</style>
