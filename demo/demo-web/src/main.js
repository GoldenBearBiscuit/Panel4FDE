import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import App from './App.vue';
import OrderList from './views/OrderList.vue';
import OrderDetail from './views/OrderDetail.vue';
import GraphView from './views/GraphView.vue';
import { initObserve } from './observe.js';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/order/list' },
    { path: '/order/list', component: OrderList, meta: { title: '订单列表' } },
    { path: '/order/:id', component: OrderDetail, meta: { title: '订单详情' } },
    // ★ 注意不能用 /observe 前缀：那个路径被代理到后端接口了
    { path: '/graph', component: GraphView, meta: { title: '操作路线图' } },
  ],
});

createApp(App).use(router).mount('#app');

// ══════════════════════════════════════════════════════════════
//  ★ 插件的全部前端接入成本 —— 就这一行
// ══════════════════════════════════════════════════════════════
initObserve({ router, config: { debug: true } });
