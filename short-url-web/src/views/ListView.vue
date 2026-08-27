<template>
  <div class="list-view">
    <el-row justify="center">
      <el-col :span="20">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>📋 短链列表</span>
              <div>
                <el-link type="primary" @click="$router.push('/create')">← 返回创建</el-link>
                <el-button :icon="Refresh" @click="loadData" style="margin-left: 8px">刷新</el-button>
              </div>
            </div>
          </template>

          <el-table :data="records" v-loading="loading" stripe>
            <el-table-column prop="id" label="ID" width="90" />
            <el-table-column prop="shortCode" label="短链码" width="100">
              <template #default="{ row }">
                <el-tag type="success">{{ row.shortCode }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="短链" min-width="260">
              <template #default="{ row }">
                <div class="short-url-cell">
                  <el-input :model-value="row.shortUrl" readonly size="small" />
                  <el-button size="small" :icon="DocumentCopy" @click="copyUrl(row.shortUrl)" />
                  <el-button size="small" type="primary" :icon="View" @click="openUrl(row.shortUrl)" />
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="originalUrl" label="原始链接" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <a :href="row.originalUrl" target="_blank">{{ row.originalUrl }}</a>
              </template>
            </el-table-column>
            <el-table-column prop="clickCount" label="点击数" width="90" align="center">
              <template #default="{ row }">
                <el-tag>{{ row.clickCount }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="170" />
          </el-table>

          <el-pagination
            v-model:current-page="current"
            v-model:page-size="size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadData"
            @current-change="loadData"
            style="margin-top: 16px; justify-content: flex-end"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, DocumentCopy, View } from '@element-plus/icons-vue'
import copy from 'copy-to-clipboard'
import { listShortUrl } from '../api/shortUrl'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const current = ref(1)
const size = ref(10)

async function loadData() {
  loading.value = true
  try {
    const res = await listShortUrl(current.value, size.value)
    records.value = res.data.records
    total.value = res.data.total
  } catch (e) {
  } finally {
    loading.value = false
  }
}

function copyUrl(url) {
  if (copy(url)) {
    ElMessage.success('已复制')
  }
}

function openUrl(url) {
  window.open(url, '_blank')
}

onMounted(loadData)
</script>

<style scoped>
.list-view {
  max-width: 1200px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.short-url-cell {
  display: flex;
  gap: 4px;
}
.short-url-cell .el-input {
  flex: 1;
}
</style>