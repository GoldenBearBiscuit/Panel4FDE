<template>
  <div class="card">
    <div class="row">
      <h2>订单详情 #{{ id }}</h2>
      <button id="btn-back" @click="$router.push('/order/list')">返回列表</button>
      <button id="btn-go-graph" @click="$router.push('/graph')">全屏观测台</button>
    </div>

    <template v-if="order.id">
      <label>客户</label>
      <input id="input-customer" v-model="order.customer" name="customer" />

      <label>状态</label>
      <select id="select-status" v-model="order.status" name="status">
        <option value="NEW">NEW</option>
        <option value="PAID">PAID</option>
        <option value="SHIPPED">SHIPPED</option>
        <option value="CLOSED">CLOSED</option>
      </select>

      <div style="margin-top: 14px">
        <button id="btn-save" class="primary" @click="save">保存</button>
        <span v-if="msg" style="margin-left: 12px; color: #2a7">{{ msg }}</span>
      </div>
      <p class="hint">
        保存会依次触发：<code>POST /api/order/{id}/save</code> → SQL UPDATE → Redis DEL/SET。
        三层事件都会被采集并挂到同一个 API 节点下——右侧左栏实时可见。
      </p>
    </template>
    <p v-else class="hint">加载中…</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();
const id = route.params.id;
const order = ref({});
const msg = ref('');

async function load() {
  const res = await fetch(`/api/order/${id}`);
  order.value = await res.json();
}

async function save() {
  msg.value = '';
  const res = await fetch(`/api/order/${id}/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ customer: order.value.customer, status: order.value.status }),
  });
  const data = await res.json();
  msg.value = res.ok ? `已保存（影响 ${data.updated} 行）` : '保存失败';
}
onMounted(load);
</script>
