<template>
  <!-- ★ data-observe-ignore：面板与业务同页时，这里的点击不能被采集，
       否则一边看图一边污染图（点一次「刷新」就多一个节点） -->
  <div class="live" :class="mode" data-observe-ignore>
    <!-- 控制条 -->
    <div class="bar">
      <select v-model="selected" @change="switchSession(selected)" class="sel">
        <option value="">（最近会话）</option>
        <option v-for="s in sessions" :key="s.session_id" :value="s.session_id">
          {{ short(s.session_id) }} · {{ s.event_count }} 事件
        </option>
      </select>
      <label class="chk"><input type="checkbox" v-model="auto" /> 自动</label>
      <label class="chk" title="只保留 TRIGGER / NAVIGATE / CALL 边与相关节点，滤掉页面自动发的请求（AUTO）和时序兜底（PRECEDES）">
        <input id="chk-human" type="checkbox" v-model="humanOnly" @change="render()" /> 只看人为
      </label>
      <button id="btn-reload" @click="reload" title="重新拉取当前会话">刷新</button>
      <button id="btn-current" @click="useCurrent" title="跳到当前浏览器会话">当前会话</button>
      <span class="seg">
        <button id="btn-view-flow" :class="{ on: viewMode === 'flow' }" @click="setView('flow')" title="按调用关系自动布局">流程图</button>
        <button id="btn-view-swim" :class="{ on: viewMode === 'swim' }" @click="setView('swim')" title="泳道图：横向=时序，纵向=层">泳道图</button>
      </span>
      <button id="btn-fit" @click="fitAll" title="缩放到一屏">适应画布</button>
      <span class="stat" :title="statsTip">
        <b>{{ events.length }}</b>/<b>{{ d.nodeCount || 0 }}</b>/<b>{{ d.edgeCount || 0 }}</b>
        <em v-if="dropped" class="warn" title="采集队列丢弃数">丢{{ dropped }}</em>
      </span>
    </div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="body">
      <!-- 实时事件流 -->
      <section class="stream">
        <div class="panel-head">
          实时记录
          <span v-if="newCount" class="badge">{{ newCount }} 条新</span>
          <span class="hint-inline">
            {{ idleHint }}
          </span>
        </div>
        <div ref="listEl" class="stream-list" @scroll="onScroll">
          <div v-if="!events.length" class="empty">
            还没有事件。<br />在{{ mode === 'stack' ? '左侧' : '另一个标签页' }}操作，这里会实时长出来。
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
            <span
              v-if="e.edge_type && e.edge_type !== 'PRECEDES'"
              class="edge"
              :class="e.edge_type === 'AUTO' ? 'edge-auto' : 'edge-human'"
            >←{{ e.edge_type }}</span>
            <span v-if="e.duration_ms != null" class="ms">{{ e.duration_ms }}ms</span>
            <span v-if="e.status === 'ERROR'" class="err-tag">ERROR</span>
          </div>
        </div>
      </section>

      <!-- 图 -->
      <section class="graphbox">
        <div class="panel-head">
          操作路线图
          <span class="legend">
            <i style="background: #5b8ff9"></i>前端
            <i style="background: #f6bd16"></i>后端
            <i style="background: #5ad8a6"></i>资源层
            <i class="ln-human"></i>实线=人为
            <i class="ln-auto"></i>浅线=自动
            <i class="ln-dash"></i>虚线=PRECEDES
          </span>
          <span class="hint-inline">可拖拽/缩放</span>
        </div>
        <!-- 流程图：按调用关系自动布局（dagre，异步） -->
        <div v-if="viewMode === 'flow'" ref="el" class="canvas"></div>

        <!-- 泳道图：固定布局，x = 步序，y = 层。不用 dagre，故无异步收敛问题 -->
        <div v-else class="swim-wrap">
          <div ref="labelCol" class="swim-labels" :style="{ height: swim.contentH + 'px' }">
            <div
              v-for="(l, i) in swim.lanes"
              :key="l.key"
              class="lane-label"
              :style="{
                top: swim.laneY[i] + 'px',
                height: swim.laneH[i] + 'px',
                color: l.color,
                borderLeftColor: l.color,
              }"
            >
              {{ l.name }}
            </div>
          </div>
          <div class="swim-scroll" @scroll="onSwimScroll">
            <div class="swim-inner" :style="{ width: swim.contentW + 'px', height: swim.contentH + 'px' }">
              <div
                v-for="(l, i) in swim.lanes"
                :key="'b' + l.key"
                class="lane-band"
                :style="{ top: swim.laneY[i] + 'px', height: swim.laneH[i] + 'px', background: l.bg }"
              ></div>
              <div
                v-for="(c, i) in swim.cols"
                :key="'c' + i"
                class="col-head"
                :style="{ left: swim.colX[i] + 'px', width: swim.colW[i] + 'px' }"
              >
                {{ c }}
              </div>
              <div
                ref="el"
                class="swim-canvas"
                :style="{ width: swim.contentW + 'px', height: swim.contentH + 'px' }"
              ></div>
            </div>
          </div>
        </div>

        <div v-if="!d.nodeCount" class="empty">还没有节点。</div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue';
import G6 from '@antv/g6';
import { getSessionId } from '../observe.js';

const props = defineProps({
  /** side = 事件流左 / 图右（宽页面）；stack = 事件流上 / 图下（窄列） */
  mode: { type: String, default: 'side' },
});

const el = ref(null);
const listEl = ref(null);
const sessions = ref([]);
const selected = ref('');
const events = ref([]);
const d = ref({});
const error = ref(null);
const auto = ref(true);
/** 只看人为操作：滤掉 AUTO（页面自动发的请求）与 PRECEDES（时序兜底） */
const humanOnly = ref(false);
/** flow = 按调用关系自动布局；swim = 泳道图（x=步序，y=层） */
const viewMode = ref('flow');
const labelCol = ref(null);
const NODE_H = 30;
const NODE_GAP = 8;
const SWIM_TOP = 26;
const LANE_DEF = [
  { key: 'FRONTEND', name: '前端', color: '#5b8ff9', bg: 'rgba(91,143,249,0.06)' },
  { key: 'BACKEND', name: '后端', color: '#f6bd16', bg: 'rgba(246,189,22,0.08)' },
  { key: 'RESOURCE', name: '资源层', color: '#5ad8a6', bg: 'rgba(90,216,166,0.08)' },
];
const swim = ref({ lanes: LANE_DEF, laneY: [], laneH: [], cols: [], colX: [], colW: [], contentW: 0, contentH: 0 });
const newCount = ref(0);
const dropped = ref(0);
const statsTip = ref('');

let graph = null;
let timer = null;
let lastId = 0;
let lastSig = '';
let stuckToBottom = true;
let flashTimers = [];
let tickCount = 0;

const COLOR = { FRONTEND: '#5b8ff9', BACKEND: '#f6bd16', RESOURCE: '#5ad8a6' };
const LABEL = { FRONTEND: '前端', BACKEND: '后端', RESOURCE: '资源' };

const layerOf = (l) => ({ c: COLOR[l] || '#999', t: LABEL[l] || l || '?' });
const short = (s) => (s ? String(s).slice(0, 8) : '-');
const idleHint = computed(() =>
  props.mode === 'stack' ? '左侧操作 → 这里实时更新' : '另一标签页操作 → 这里实时更新'
);

function hms(v) {
  if (v == null) return '';
  const x = new Date(v);
  if (isNaN(x.getTime())) return String(v).slice(11, 23);
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${p(x.getHours())}:${p(x.getMinutes())}:${p(x.getSeconds())}.${p(x.getMilliseconds(), 3)}`;
}

// ── 事件流 ────────────────────────────────────────────────────

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

    const ids = j.events.map((e) => e.id);
    flashTimers.push(
      setTimeout(() => {
        for (const e of events.value) if (ids.includes(e.id)) e._new = false;
      }, 1500)
    );

    await nextTick();
    if (stuckToBottom && listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight;
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

// ── 图 ───────────────────────────────────────────────────────

const MIN_CANVAS_H = 320;
const MAX_CANVAS_H = 3200;
let fitting = false;

async function pullGraph(force) {
  if (!selected.value) return;
  try {
    const g = await (await fetch(`/observe/graph?sessionId=${encodeURIComponent(selected.value)}`)).json();
    const sig = `${g.eventCount}/${g.nodeCount}/${g.edgeCount}`;
    d.value = g;
    if (!force && sig === lastSig) return; // ★ 数据没变就不重绘，避免每秒重排闪烁
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

function truncate(s, n) {
  s = String(s == null ? '' : s);
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/**
 * ★ 流程图的布局收敛定时器必须可取消。
 *   否则切到泳道图后，这些定时器才触发，会在**泳道图**上跑 changeSize/translate
 *   把布局搞歪，并覆盖 __graphInfo（已由验收脚本捕获：laneViolations 变 undefined）。
 */
let flowTimers = [];
function clearFlowTimers() {
  for (const t of flowTimers) clearTimeout(t);
  flowTimers = [];
}

/** 估算节点宽度（与文字长度挂钩，让布局知道标签会占多宽） */
function nodeW(label) {
  const s = truncate(label, 22);
  return Math.max(66, Math.min(260, s.length * 7.6 + 22));
}

function setGraphInfo(nodes, edges, w, h, extra) {
  try {
    window.__graphInfo = Object.assign(
      {
        mode: viewMode.value,
        nodes: nodes,
        edges: edges,
        zoom: graph ? graph.getZoom() : null,
        canvasW: w,
        canvasH: h,
        layout: graph ? 'ok' : 'failed',
      },
      extra || {}
    );
  } catch (e) {
    /* 忽略 */
  }
}

function setView(m) {
  if (viewMode.value === m) return;
  viewMode.value = m;
  clearFlowTimers();
  if (graph) {
    graph.destroy();
    graph = null;
  }
  nextTick(() => render());
}

function onSwimScroll(e) {
  // 泳道名固定不动，靠反向位移跟随纵向滚动
  if (labelCol.value) labelCol.value.style.transform = `translateY(${-e.target.scrollTop}px)`;
}

function render() {
  return viewMode.value === 'swim' ? renderSwim() : renderFlow();
}

// ── 泳道图：x = 步序，y = 层。固定布局，不用 dagre（故无异步收敛问题） ───
function renderSwim() {
  const data = d.value;
  clearFlowTimers();
  if (graph) {
    graph.destroy();
    graph = null;
  }
  if (!data || !data.nodes || !data.nodes.length) {
    swim.value = { lanes: LANE_DEF, laneY: [], laneH: [], cols: [], colX: [], colW: [], contentW: 0, contentH: 0 };
    return;
  }

  let ns = data.nodes.slice();
  let es = data.edges.slice();
  if (humanOnly.value) {
    es = es.filter((e) => e.type !== 'AUTO' && e.type !== 'PRECEDES');
    const kept = new Set();
    es.forEach((e) => { kept.add(e.source); kept.add(e.target); });
    ns = ns.filter((n) => kept.has(n.id));
  } else {
    // 泳道图里 x 轴已经是时序，PRECEDES 纯属噪声
    es = es.filter((e) => e.type !== 'PRECEDES');
  }
  if (!ns.length) {
    swim.value = { lanes: LANE_DEF, laneY: [], laneH: [], cols: [], colX: [], colW: [], contentW: 0, contentH: 0 };
    error.value = humanOnly.value ? '当前会话里还没有“人为触发”的节点；取消勾选「只看人为」看全部。' : null;
    return;
  }
  error.value = null;

  // 1) 列 = 去重排序的步序
  const steps = Array.from(new Set(ns.map((n) => n.step || 0))).sort((a, b) => a - b);
  const colOf = {};
  steps.forEach((s, i) => { colOf[s] = i; });

  // 2) 列宽 = 该列最宽节点 + 间距
  const colW = steps.map((s) => {
    const ws = ns.filter((n) => (n.step || 0) === s).map((n) => nodeW(n.label));
    return Math.max(70, ...ws) + 34;
  });
  const colX = [];
  let acc = 12;
  steps.forEach((s, i) => { colX.push(acc); acc += colW[i]; });
  const contentW = acc + 12;

  // 3) 每层的高度 = 该层最多的单元格内节点数
  const laneH = LANE_DEF.map((l) => {
    const per = {};
    ns.filter((n) => n.layer === l.key).forEach((n) => {
      const k = n.step || 0;
      per[k] = (per[k] || 0) + 1;
    });
    const rows = Math.max(1, ...Object.values(per));
    return rows * (NODE_H + NODE_GAP) + NODE_GAP;
  });
  const laneY = [];
  let y = SWIM_TOP;
  laneH.forEach((h) => { laneY.push(y); y += h + 6; });
  const contentH = y + 8;

  // 4) 节点坐标
  const seen = {};
  const nodes = ns.map((n) => {
    let li = LANE_DEF.findIndex((l) => l.key === n.layer);
    if (li < 0) li = 1;
    const ci = colOf[n.step || 0] || 0;
    const k = li + '|' + ci;
    const idx = seen[k] === undefined ? (seen[k] = 0) : (seen[k] = seen[k] + 1);
    const w = nodeW(n.label);
    return {
      id: n.id,
      label: truncate(n.label, 22),
      type: 'rect',
      size: [w, NODE_H],
      x: colX[ci] + colW[ci] / 2,
      y: laneY[li] + NODE_GAP + idx * (NODE_H + NODE_GAP) + NODE_H / 2,
      style: {
        fill: (COLOR[n.layer] || '#999') + '2e',
        stroke: COLOR[n.layer] || '#999',
        lineWidth: 1.4,
        radius: 4,
      },
      labelCfg: { style: { fontSize: 11, fill: '#222' } },
    };
  });

  const SWIM_EDGE = {
    TRIGGER: { stroke: '#5a6b7d', endArrow: true },
    NAVIGATE: { stroke: '#5a6b7d', endArrow: true },
    AUTO: { stroke: '#c9ccd1', endArrow: true },
    CALL: { stroke: '#8aa4a0', endArrow: true },
  };
  const edges = es.map((e, i) => ({
    id: 'e' + i,
    source: e.source,
    target: e.target,
    edgeType: e.type,
    // CALL 数量多，省掉文字标签，颜色已能表达
    label: e.type === 'CALL' ? '' : e.type,
    style: SWIM_EDGE[e.type] || { stroke: '#9aa4b2', endArrow: true },
    labelCfg: { style: { fontSize: 9, fill: '#7a8290' } },
  }));

  swim.value = {
    lanes: LANE_DEF,
    laneY,
    laneH,
    colX,
    colW,
    cols: steps.map((s, i) => '#' + (i + 1)),
    contentW,
    contentH,
  };

  if (!el.value) return;
  graph = new G6.Graph({
    container: el.value,
    width: contentW,
    height: contentH,
    fitView: false,
    zoom: 1,
    // 位置是算出来的，禁用拖拽节点（否则用户能拖乱布局）
    modes: { default: ['drag-canvas', 'zoom-canvas'] },
    defaultNode: { type: 'rect', size: [90, NODE_H] },
    defaultEdge: { type: 'line' },
    animate: false,
  });
  graph.data({ nodes, edges });
  graph.render();

  // ★ 泳道自检：每个节点必须落在它所属层的泳道带内。
  //   泳道图的核心正确性约束，出错了图就失去含义。
  let violations = 0;
  for (let i = 0; i < ns.length; i++) {
    let li = LANE_DEF.findIndex((l) => l.key === ns[i].layer);
    if (li < 0) li = 1;
    const top = laneY[li];
    const bottom = top + laneH[li];
    if (!(nodes[i].y >= top && nodes[i].y <= bottom)) violations++;
  }
  setGraphInfo(nodes.length, edges.length, contentW, contentH, {
    laneCount: LANE_DEF.length,
    laneViolations: violations,
    colCount: steps.length,
  });
}

// ── 流程图：按调用关系自动布局 ─────────────────────────────
function contentBounds() {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  graph.getNodes().forEach((nd) => {
    const m = nd.getModel();
    if (typeof m.x !== 'number' || typeof m.y !== 'number') return;
    const halfW = (Array.isArray(m.size) ? m.size[0] : 80) / 2 + 8;
    const halfH = (Array.isArray(m.size) ? m.size[1] : 32) / 2 + 8;
    minX = Math.min(minX, m.x - halfW);
    maxX = Math.max(maxX, m.x + halfW);
    minY = Math.min(minY, m.y - halfH);
    maxY = Math.max(maxY, m.y + halfH);
  });
  return isFinite(minX) ? { minX, minY, maxX, maxY } : null;
}

function fitCanvasToContent() {
  // ★ 模式守卫：切到泳道图后，本函数不得再动图（它的定位逻辑是给 dagre 写的）
  if (viewMode.value !== 'flow') return;
  if (!graph || fitting || !el.value) return;
  fitting = true;
  try {
    const b = contentBounds();
    if (!b) return;
    const boxW = el.value.clientWidth || 600;
    const contentW = Math.ceil(b.maxX - b.minX) + 110;
    const contentH = Math.ceil(b.maxY - b.minY) + 70;
    const w = Math.min(6000, Math.max(boxW, contentW));
    const h = Math.min(MAX_CANVAS_H, Math.max(MIN_CANVAS_H, contentH));
    if (el.value.clientHeight !== h) {
      el.value.style.height = h + 'px';
    }
    // ★ changeSize 必须在 translate 之前：它可能重置视口变换
    graph.changeSize(w, h);

    // ★ translate 是「相对当前变换」，不是绝对设置。
    //   布局收敛期会调多次 → 偏移会累积（已由截图发现：内容越跑越偏）。
    //   所以必须用「当前画布包围盒」反推位移，这样调多少次都收敛到同一位置。
    try {
      const cb = graph.getGroup().getCanvasBBox();
      if (cb && cb.width > 0) {
        graph.translate(70 - cb.minX, 30 - cb.minY);
      } else {
        graph.translate(70 - b.minX, 30 - b.minY);
      }
    } catch (e3) {
      graph.translate(70 - b.minX, 30 - b.minY); // 兑底：拿不到包围盒就用推算值
    }
    window.__graphBounds = b;
    try {
      let cb = null;
      try {
        const b2 = graph.getGroup().getCanvasBBox();
        cb = { x: Math.round(b2.minX), y: Math.round(b2.minY), w: Math.round(b2.width), h: Math.round(b2.height) };
      } catch (e2) { /* ignore */ }
      window.__graphInfo = {
        // 用图上实际数量（而非全量数据），这样过滤开关的效果也可被断言
        nodes: graph.getNodes().length,
        edges: graph.getEdges().length,
        zoom: graph.getZoom(),
        canvasW: el.value.clientWidth,
        canvasH: el.value.clientHeight,
        bounds: b,
        canvasBBox: cb,
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

function renderFlow() {
  const data = d.value;
  if (!el.value) return;
  if (graph) {
    graph.destroy();
    graph = null;
  }
  if (!data || !data.nodes || !data.nodes.length) return;

  // 边的样式：颜色 = 语义。深色=人为，浅灰=页面自动，绿灰=资源调用，极浅虚=时序兜底
  const EDGE_STYLE = {
    TRIGGER: { stroke: '#5a6b7d', endArrow: true },
    NAVIGATE: { stroke: '#5a6b7d', endArrow: true },
    AUTO: { stroke: '#c9ccd1', endArrow: true },
    CALL: { stroke: '#8aa4a0', endArrow: true },
    PRECEDES: { stroke: '#e3e5e8', lineDash: [6, 5], endArrow: true },
  };

  // ★ 节点用「宽框 + 文字放框内」而不是「小图形 + 下方标签」：
  //   dagre 按节点尺寸排布，不知道标签会溢出节点框，
  //   用小图形时相邻节点的标签会互相压在一起读不了（已由截图发现）。
  //   把标签宽度作为节点尺寸交给布局，就不会重叠。层用颜色区分（形状不再承载信息）。
  let rawNodes = data.nodes.map((n) => {
    const label = truncate(n.label, 26);
    const w = Math.max(64, Math.min(280, label.length * 7.6 + 22));
    return {
      id: n.id,
      label,
      type: 'rect',
      size: [w, 32],
      style: {
        fill: (COLOR[n.layer] || '#999') + '26',
        stroke: COLOR[n.layer] || '#999',
        lineWidth: 1.4,
        radius: 4,
      },
      labelCfg: { style: { fontSize: 11, fill: '#222' } },
    };
  });

  let rawEdges = data.edges.map((e, i) => ({
    id: 'e' + i,
    source: e.source,
    target: e.target,
    edgeType: e.type,
    label: e.type === 'PRECEDES' ? '' : e.type,
    style: EDGE_STYLE[e.type] || { stroke: '#9aa4b2', endArrow: true },
    labelCfg: { style: { fontSize: 10, fill: '#7a8290' } },
  }));

  // 「只看人为」：先筛边，再筛节点（只保留出现在保留边上的节点）
  let nodes = rawNodes;
  let edges = rawEdges;
  if (humanOnly.value) {
    edges = rawEdges.filter((e) => e.edgeType !== 'AUTO' && e.edgeType !== 'PRECEDES');
    const kept = new Set();
    for (const e of edges) {
      kept.add(e.source);
      kept.add(e.target);
    }
    nodes = rawNodes.filter((n) => kept.has(n.id));
  }

  if (!nodes.length) {
    error.value = humanOnly.value
      ? '当前会话里还没有“人为触发”的节点。取消勾选「只看人为」看全部，或先去左侧点几下。'
      : null;
    return;
  }
  error.value = null;

  const makeCfg = (layout) => ({
    container: el.value,
    width: el.value.clientWidth || 600,
    height: MIN_CANVAS_H,
    fitView: false,
    zoom: 1,
    modes: { default: ['drag-canvas', 'zoom-canvas', 'drag-node'] },
    layout,
    defaultNode: { size: [80, 32] },
    defaultEdge: { type: 'polyline' },
    animate: false,
  });

  try {
    // TB：时间从上往下。宽框后 dagre 会按“标签宽度”分配水平空间，不会重叠
    graph = new G6.Graph(makeCfg({ type: 'dagre', rankdir: 'TB', nodesep: 18, ranksep: 40 }));
    graph.on('afterlayout', fitCanvasToContent);
    graph.data({ nodes, edges });
    graph.render();
    // ★ 多次复算：dagre 在节点多时不是一次就稳定，400ms 后量到的尺寸会偏小，
    //   导致画布定窄了、右边缘被截（已由截图发现）。
    //   实测 400/1000/1800ms 三次收敛；fitting 互斥锁防重入；定时器可取消。
    clearFlowTimers();
    flowTimers.push(setTimeout(fitCanvasToContent, 400));
    flowTimers.push(setTimeout(fitCanvasToContent, 1000));
    flowTimers.push(setTimeout(fitCanvasToContent, 1800));
  } catch (e) {
    error.value = 'dagre 失败，降级 force：' + e.message;
    try {
      graph = new G6.Graph(makeCfg({ type: 'force', linkDistance: 160, preventOverlap: true }));
      graph.on('afterlayout', fitCanvasToContent);
      graph.data({ nodes, edges });
      graph.render();
      clearFlowTimers();
      flowTimers.push(setTimeout(fitCanvasToContent, 400));
      flowTimers.push(setTimeout(fitCanvasToContent, 1000));
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

// ── 会话与轮询 ───────────────────────────────────────────────

async function loadSessions() {
  try {
    const list = await (await fetch('/observe/sessions?limit=20')).json();
    const cur = selected.value;
    // ★ 当前会话可能还没出现在列表里（事件刚产生、还没落库）→
    //   不补占位项的话，下拉框会显示空白（已由截图发现）
    if (cur && !list.some((s) => String(s.session_id) === cur)) {
      sessions.value = [{ session_id: cur, event_count: events.value.length, node_count: 0 }].concat(list);
    } else {
      sessions.value = list;
    }
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
  await switchSession(getSessionId() || '');
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
    // 会话列表降频刷新（每 ~10s），让下拉框选项跟上
    if (++tickCount % 10 === 0) await loadSessions();
  }
  timer = setTimeout(tick, auto.value ? 1000 : 5000);
}

onMounted(async () => {
  await loadSessions();
  // ★ 默认盯当前浏览器会话：同页操作时这才是你想看的那条线
  const cur = getSessionId();
  const fallback = sessions.value.length ? String(sessions.value[0].session_id) : '';
  await switchSession(cur || fallback);
  tick();
});

onUnmounted(() => {
  if (timer) clearTimeout(timer);
  clearFlowTimers();
  for (const t of flashTimers) clearTimeout(t);
  flashTimers = [];
  if (graph) graph.destroy();
  graph = null;
});
</script>

<style scoped>
.live { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.live.side { flex-direction: column; }

/* 控制条 */
.bar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding-bottom: 6px; flex: none; }
.sel { max-width: 190px; font-size: 12px; padding: 3px 6px; }
.chk { display: flex; align-items: center; gap: 3px; font-size: 12px; color: #555; white-space: nowrap; }
.bar button { padding: 3px 8px; font-size: 12px; }
.stat { margin-left: auto; font-size: 11px; color: #666; white-space: nowrap; }
.stat em.warn { color: #b33; font-style: normal; margin-left: 4px; }

/* 主体：side = 事件流左 / 图右；stack = 事件流上 / 图下 */
.body { display: flex; gap: 10px; flex: 1; min-height: 0; }
.side .body { flex-direction: row; }
.stack .body { flex-direction: column; }

.stream { display: flex; flex-direction: column; min-height: 0;
          border: 1px solid #eef0f2; border-radius: 4px; background: #fff; }
.side .stream { width: 400px; flex: none; }
/* stack（同页右栏）：事件流封顶，把空间让给图 */
.stack .stream { flex: none; height: 30%; max-height: 250px; min-height: 110px; }
.stack .stat { margin-left: 0; }

.stream-list { flex: 1; overflow: auto; padding: 3px 0; }
.ev { display: flex; align-items: baseline; gap: 5px; padding: 1px 8px; font-size: 11.5px;
      font-family: ui-monospace, Consolas, monospace; white-space: nowrap; line-height: 1.65;
      border-left: 3px solid transparent; transition: background 0.4s; }
.ev:hover { background: #f4f7fb; }
.ev.is-new { background: #eaf4ff; border-left-color: #5b8ff9; }
.ev.is-err { background: #fff3f3; }
.ev .t { color: #9aa4b2; flex: none; }
.ev .dot { width: 7px; height: 7px; border-radius: 50%; flex: none; align-self: center; }
.ev .ly { flex: none; width: 26px; }
.ev .et { flex: none; width: 68px; color: #666; }
.ev .lb { color: #222; overflow: hidden; text-overflow: ellipsis; }
.ev .edge { color: #b0b7c3; flex: none; }
.ev .edge-human { color: #5a6b7d; font-weight: 600; }
.ev .edge-auto { color: #c9ccd1; }
.ev .ms { color: #9aa4b2; flex: none; }
.ev .err-tag { color: #b33; font-weight: 600; flex: none; }

.graphbox { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column;
            border: 1px solid #eef0f2; border-radius: 4px; background: #fcfcfd; }
.graphbox .canvas { overflow: auto; flex: 1; min-height: 0; cursor: grab; }

/* ── 泳道图 ── */
.seg { display: inline-flex; }
.seg button { border-radius: 0; margin-left: -1px; }
.seg button:first-child { border-radius: 4px 0 0 4px; margin-left: 0; }
.seg button:last-child { border-radius: 0 4px 4px 0; }
.seg button.on { background: #3367d6; color: #fff; border-color: #3367d6; }

.swim-wrap { display: flex; flex: 1; min-height: 0; overflow: hidden; }
.swim-labels { width: 64px; flex: none; position: relative; background: #fafbfc;
               border-right: 1px solid #eef0f2; will-change: transform; }
.lane-label { position: absolute; left: 0; right: 0; display: flex; align-items: center;
              justify-content: center; font-size: 11px; font-weight: 600;
              border-left: 3px solid #999; }
.swim-scroll { flex: 1; min-width: 0; overflow: auto; }
.swim-inner { position: relative; }
.lane-band { position: absolute; left: 0; right: 0; }
.col-head { position: absolute; top: 5px; text-align: center; font-size: 10px; color: #9aa4b2;
            font-family: ui-monospace, Consolas, monospace; }
.swim-canvas { position: absolute; top: 0; left: 0; }
.swim-canvas canvas { background: transparent; }

.panel-head { display: flex; align-items: center; gap: 8px; padding: 5px 9px; font-size: 12px;
              font-weight: 600; color: #444; border-bottom: 1px solid #eef0f2; background: #fafbfc;
              border-radius: 4px 4px 0 0; flex: none; }
.hint-inline { font-weight: 400; color: #9aa4b2; font-size: 11px; }
.badge { background: #5b8ff9; color: #fff; border-radius: 8px; padding: 0 6px; font-size: 10px; font-weight: 500; }
.legend { display: flex; align-items: center; gap: 3px; font-weight: 400; color: #666; font-size: 11px; }
.legend i { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-left: 5px; }
.legend i.dash { background: none; border-top: 2px dashed #ccc; height: 0; width: 12px; margin-top: 4px; }
.legend i.ln-human { background: #5a6b7d; height: 2px; width: 12px; border-radius: 0; }
.legend i.ln-auto { background: #c9ccd1; height: 2px; width: 12px; border-radius: 0; }
.legend i.ln-dash { background: none; border-top: 2px dashed #e3e5e8; height: 0; width: 12px; margin-top: 4px; }
.empty { padding: 20px; color: #9aa4b2; font-size: 12px; line-height: 1.9; text-align: center; }
.err { margin: 6px 0; padding: 7px 9px; background: #fff3f3; border: 1px solid #ffd0d0;
       color: #b33; font-size: 12px; border-radius: 4px; flex: none; }
</style>
