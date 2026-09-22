<template>
  <div class="card">
    <div class="row">
      <h2>操作路线图</h2>
      <select v-model="selected" @change="load(selected)" style="width: 300px">
        <option value="">（最近会话）</option>
        <option v-for="s in sessions" :key="s.session_id" :value="s.session_id">
          {{ short(s.session_id) }} · {{ s.event_count }} 事件 · {{ s.node_count }} 节点
        </option>
      </select>
      <button id="btn-reload" @click="load(selected)">刷新</button>
      <button id="btn-current" @click="loadCurrent">看当前会话</button>
      <button id="btn-fit" @click="fitAll">适应画布</button>
    </div>

    <div class="stats">
      <span>事件 <b>{{ d.eventCount || 0 }}</b></span>
      <span>节点 <b>{{ d.nodeCount || 0 }}</b></span>
      <span>边 <b>{{ d.edgeCount || 0 }}</b></span>
      <span class="legend">
        <i style="background: #5b8ff9"></i>前端
        <i style="background: #f6bd16"></i>后端
        <i style="background: #5ad8a6"></i>资源层
      </span>
      <span class="legend"><i style="background: #ccc"></i>虚线 = PRECEDES（时序兜底）</span>
      <span class="tip">画布内可拖拽平移、滚轮缩放</span>
    </div>

    <div v-if="error" class="err">{{ error }}</div>
    <div ref="el" class="canvas"></div>
    <p v-if="!d.nodeCount" class="hint">
      还没有数据。先去「订单列表」点几下、改一下订单再保存，然后回到这里。
    </p>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue';
import G6 from '@antv/g6';
import { getSessionId } from '../observe.js';

const el = ref(null);
const sessions = ref([]);
const selected = ref('');
const d = ref({});
const error = ref(null);
let graph = null;

const COLOR = { FRONTEND: '#5b8ff9', BACKEND: '#f6bd16', RESOURCE: '#5ad8a6' };
const SHAPE = { FRONTEND: 'circle', BACKEND: 'rect', RESOURCE: 'diamond' };

function short(s) {
  return s ? String(s).slice(0, 8) : '-';
}

async function loadSessions() {
  try {
    const res = await fetch('/observe/sessions?limit=20');
    sessions.value = await res.json();
  } catch (e) {
    /* 忽略 */
  }
}

async function load(sessionId) {
  error.value = null;
  try {
    const url = sessionId ? `/observe/graph?sessionId=${encodeURIComponent(sessionId)}` : '/observe/graph';
    const res = await fetch(url);
    d.value = await res.json();
    selected.value = d.value.sessionId || '';
    await nextTick();
    render();
    loadSessions();
  } catch (e) {
    error.value = '拉取图数据失败: ' + e;
  }
}

function loadCurrent() {
  const sid = getSessionId();
  if (sid) load(sid);
  else load('');
}

function truncate(s, n) {
  s = String(s == null ? '' : s);
  return s.length > n ? s.slice(0, n) + '…' : s;
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
    // ★ 注意：这里的 type 是 G6 的图形名，不是业务类型
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
    // ★ 业务边类型放进 edgeType，绝不能占用 G6 的 type（那是图形名）
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
    width: el.value.clientWidth || 900,
    height: 620,
    // ★ 不开 fitView。会话是一条很长的链：fitView 会把它压到 0.3 倍，文字完全读不了。
    //   正确做法是「原尺寸 + 纵向排布 + 让页面滚动」——像读时间线一样。
    fitView: false,
    zoom: 1,
    modes: { default: ['drag-canvas', 'zoom-canvas', 'drag-node'] },
    layout,
    defaultNode: { size: 38 },
    defaultEdge: { type: 'polyline' },
    animate: false,
  });

  const MIN_CANVAS_H = 620;
  const MAX_CANVAS_H = 3200;

  /**
   * 自己从布局后的节点坐标算内容包围盒。
   * 不用 graph.getGroup().getBBox() —— 实测它只返回单个节点的尺寸（126×57），
   * 会把画布算得跟一个节点一样高。
   */
  function contentBounds() {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    graph.getNodes().forEach((nd) => {
      const m = nd.getModel();
      if (typeof m.x !== 'number' || typeof m.y !== 'number') return;
      minX = Math.min(minX, m.x - 80); // 含标签宽度余量
      maxX = Math.max(maxX, m.x + 80);
      minY = Math.min(minY, m.y - 30);
      maxY = Math.max(maxY, m.y + 46);
    });
    if (!isFinite(minX)) return null;
    return { minX, minY, maxX, maxY };
  }

  let fitting = false;

  /** 按内容实际高度撑开画布，并居中，由页面滚动承载长链 */
  function fitCanvasToContent() {
    if (!graph || fitting) return;
    fitting = true;
    try {
      const b = contentBounds();
      if (!b) return;
      const w = el.value.clientWidth || 900;
      const needH = Math.ceil(b.maxY - b.minY) + 70;
      const h = Math.min(MAX_CANVAS_H, Math.max(MIN_CANVAS_H, needH));
      if (el.value.clientHeight !== h) {
        graph.changeSize(w, h);
        el.value.style.height = h + 'px';
      }
      // zoom=1 时图坐标与画布像素一致。左对齐而非居中：
      // 居中会把一条窄链推到画布右侧，左边留一大片空白，看起来像坏了
      graph.translate(70 - b.minX, 30 - b.minY);
      window.__graphBounds = b;
    } catch (e) {
      /* 忽略：布局辅助失败不影响图存在 */
    } finally {
      fitting = false;
    }
  }

  try {
    // TB：时间从上往下流，长链变成可滚动的纵向时间线，而不是被压扁的横线
    graph = new G6.Graph(makeCfg({ type: 'dagre', rankdir: 'TB', nodesep: 26, ranksep: 46 }));
    // ★ 布局是异步的：必须在 afterlayout 里才好读节点坐标。
    //   早读会拿到初始坐标，算出来的包围盒完全不对。
    graph.on('afterlayout', fitCanvasToContent);
    graph.data({ nodes, edges });
    graph.render();
    // 兜底：若布局是同步完成的（afterlayout 已过），延迟再算一次
    setTimeout(fitCanvasToContent, 350);
  } catch (e) {
    // dagre 不可用时降级为力导向，绝不白屏
    error.value = 'dagre 布局失败，已降级为 force：' + e.message;
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

  // 验收脚本读这个对象做客观判定，不靠“看起来对”
  try {
    window.__graphInfo = {
      nodes: nodes.length,
      edges: edges.length,
      zoom: graph ? graph.getZoom() : null,
      canvasH: el.value ? el.value.clientHeight : null,
      bounds: window.__graphBounds || null,
      layout: graph ? 'ok' : 'failed',
    };
  } catch (e) {
    window.__graphInfo = { err: String(e) };
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
onMounted(async () => {
  await loadSessions();
  await load('');
});
onUnmounted(() => {
  if (graph) graph.destroy();
  graph = null;
});
</script>

<style scoped>
.stats { display: flex; flex-wrap: wrap; gap: 18px; margin: 12px 0; color: #555; font-size: 12px; }
.stats b { color: #222; }
.legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px;
            margin-right: 4px; vertical-align: middle; }
.legend { margin-left: 6px; }
.canvas { width: 100%; min-height: 620px; border: 1px solid #eef0f2; border-radius: 4px;
          background: #fcfcfd; cursor: grab; }
.tip { color: #9aa4b2; margin-left: auto; }
.err { margin: 10px 0; padding: 8px 10px; background: #fff3f3; border: 1px solid #ffd0d0;
       color: #b33; font-size: 12px; border-radius: 4px; }
</style>
