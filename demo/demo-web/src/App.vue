<template>
  <div class="page">
    <header class="topbar">
      <strong>observe-kit demo</strong>
      <nav>
        <router-link to="/order/list">订单（同页观测）</router-link>
        <router-link to="/graph">全屏观测台</router-link>
      </nav>
      <span class="session" :title="sessionId">会话 {{ sessionIdShort }}</span>
    </header>

    <!--
      ★ 两栏外壳：左边是业务（router-view），右边是实时观测。
      关键点：左栏仍然是「路由页面」，所以页面切换仍然产生 NAVIGATE 边——
      如果做成纯同页 tab 切换，就丢了一整类边。
    -->
    <main :class="{ console: isConsole }">
      <template v-if="isConsole">
        <div class="col biz"><router-view /></div>
        <div class="col obs"><LivePanel mode="stack" /></div>
      </template>
      <router-view v-else />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { getSessionId } from '../src/observe.js';
import LivePanel from './components/LivePanel.vue';

const route = useRoute();
const sessionId = getSessionId();
const sessionIdShort = sessionId ? sessionId.slice(0, 8) : '-';

/** 业务页面走两栏外壳；全屏观测台保持单栏 */
const isConsole = computed(() => (route.path || '').indexOf('/order') === 0);
</script>

<style>
* { box-sizing: border-box; }
body { margin: 0; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
       background: #f5f6f8; color: #222; font-size: 14px; }
.topbar { display: flex; align-items: center; gap: 18px; padding: 9px 16px;
          background: #fff; border-bottom: 1px solid #e3e5e8; }
.topbar nav a { margin-right: 14px; color: #3367d6; text-decoration: none; }
.topbar nav a.router-link-active { font-weight: 600; text-decoration: underline; }
.session { margin-left: auto; color: #888; font-size: 12px; font-family: monospace; }

.page main { padding: 14px; }
/* 两栏外壳 */
.page main.console { display: flex; gap: 12px; height: calc(100vh - 52px); min-height: 0; }
.page main.console .col { min-height: 0; display: flex; flex-direction: column; }
.page main.console .col.biz { width: 42%; flex: none; overflow: auto; }
.page main.console .col.obs { flex: 1; min-width: 0; }

.card { background: #fff; border: 1px solid #e3e5e8; border-radius: 6px; padding: 14px; }
.row { display: flex; align-items: center; gap: 10px; }
.row h2 { margin: 0; font-size: 16px; }
button { padding: 4px 10px; border: 1px solid #c9ccd1; background: #fff; border-radius: 4px;
         cursor: pointer; font-size: 13px; }
button:hover { background: #f0f2f5; }
button.primary { background: #3367d6; color: #fff; border-color: #3367d6; }
table { width: 100%; border-collapse: collapse; margin-top: 12px; }
th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid #eef0f2; white-space: nowrap; }
th { background: #fafbfc; font-weight: 600; color: #555; font-size: 12px; }
label { display: block; margin: 9px 0 4px; color: #555; font-size: 12px; }
input, select { padding: 5px 8px; border: 1px solid #c9ccd1; border-radius: 4px; width: 240px; }
.hint { color: #888; font-size: 12px; margin-top: 10px; line-height: 1.7; }
code { background: #f2f4f7; padding: 1px 4px; border-radius: 3px; font-size: 11.5px; }
</style>
