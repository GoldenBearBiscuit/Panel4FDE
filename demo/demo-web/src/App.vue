<template>
  <div class="page">
    <header class="topbar">
      <strong>observe-kit demo</strong>
      <nav>
        <router-link to="/order/list">订单列表</router-link>
        <router-link to="/graph">操作路线图</router-link>
      </nav>
      <span class="session" :title="sessionId">会话 {{ sessionIdShort }}</span>
    </header>
    <main>
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { getSessionId } from '../src/observe.js';

const sessionId = getSessionId();
const sessionIdShort = sessionId ? sessionId.slice(0, 8) : '-';
</script>

<style>
* { box-sizing: border-box; }
body { margin: 0; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
       background: #f5f6f8; color: #222; font-size: 14px; }
.topbar { display: flex; align-items: center; gap: 20px; padding: 10px 18px;
          background: #fff; border-bottom: 1px solid #e3e5e8; }
.topbar nav a { margin-right: 14px; color: #3367d6; text-decoration: none; }
.topbar nav a.router-link-active { font-weight: 600; text-decoration: underline; }
.session { margin-left: auto; color: #888; font-size: 12px; font-family: monospace; }
.page main { padding: 18px; }
.card { background: #fff; border: 1px solid #e3e5e8; border-radius: 6px; padding: 16px; }
.row { display: flex; align-items: center; gap: 12px; }
.row h2 { margin: 0; font-size: 16px; }
button { padding: 5px 12px; border: 1px solid #c9ccd1; background: #fff; border-radius: 4px;
         cursor: pointer; font-size: 13px; }
button:hover { background: #f0f2f5; }
button.primary { background: #3367d6; color: #fff; border-color: #3367d6; }
table { width: 100%; border-collapse: collapse; margin-top: 14px; }
th, td { padding: 7px 10px; text-align: left; border-bottom: 1px solid #eef0f2; }
th { background: #fafbfc; font-weight: 600; color: #555; font-size: 12px; }
label { display: block; margin: 10px 0 4px; color: #555; font-size: 12px; }
input, select { padding: 6px 9px; border: 1px solid #c9ccd1; border-radius: 4px; width: 260px; }
.hint { color: #888; font-size: 12px; margin-top: 10px; line-height: 1.6; }
.tag { display: inline-block; padding: 1px 7px; border-radius: 3px; font-size: 11px; }
</style>
