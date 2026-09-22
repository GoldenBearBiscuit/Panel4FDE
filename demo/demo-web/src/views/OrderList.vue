<template>
  <div class="card">
    <div class="row">
      <h2>订单列表</h2>
      <button id="btn-refresh" @click="load">刷新</button>
      <button id="btn-go-graph" @click="$router.push('/graph')">全屏观测台</button>
    </div>
    <table>
      <thead>
        <tr><th>ID</th><th>订单号</th><th>客户</th><th>金额</th><th>状态</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="o in orders" :key="o.id">
          <td>{{ o.id }}</td>
          <td>{{ o.order_no }}</td>
          <td>{{ o.customer }}</td>
          <td>{{ o.amount }}</td>
          <td>{{ o.status }}</td>
          <td><button class="btn-detail" @click="open(o.id)">详情</button></td>
        </tr>
      </tbody>
    </table>
    <p class="hint">
      本页与整个前端<b>没有任何埋点代码</b>，全部事件由 observe-kit 自动采集。<br />
      右侧就是实时观测：点「详情」→ 改一下 → 保存，右边左栏会实时冒出记录，右栏的图会实时长大。<br />
      （右侧面板自身的按钮已被 <code>data-observe-ignore</code> 排除，不会污染图。）
    </p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';

const orders = ref([]);
const router = useRouter();

async function load() {
  const res = await fetch('/api/order/list');
  orders.value = await res.json();
}
function open(id) {
  router.push(`/order/${id}`);
}
onMounted(load);
</script>
