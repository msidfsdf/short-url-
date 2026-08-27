<template>
  <el-container>
    <el-header class="header">
      <h2>🔗 短链管理系统</h2>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
</script>

<style>
.header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  display: flex;
  align-items: center;
}
.header h2 {
  margin: 0;
}
</style>