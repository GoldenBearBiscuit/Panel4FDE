<template>
  <!-- 全屏观测台：事件流左 / 图右（宽页面下用 side 布局最省地方） -->
  <div class="card full">
    <h2 class="title">全屏观测台</h2>
    <LivePanel mode="side" />
  </div>
</template>

<script setup>
import LivePanel from '../components/LivePanel.vue';
</script>

<style scoped>
.full { display: flex; flex-direction: column; height: calc(100vh - 96px); }
.title { margin: 0 0 8px; font-size: 16px; flex: none; }
</style>
