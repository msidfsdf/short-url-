<template>
  <div class="create-view">
    <el-row justify="center">
      <el-col :span="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>✏️ 生成短链</span>
              <el-link type="primary" @click="$router.push('/list')">查看列表 →</el-link>
            </div>
          </template>
          <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
            <el-form-item label="原始链接" prop="originalUrl">
              <el-input
                v-model="form.originalUrl"
                placeholder="请输入长链接, 如 https://www.example.com/very/long/url"
                type="textarea"
                :rows="3"
              />
            </el-form-item>
            <el-form-item label="过期时间">
              <el-select v-model="form.expireSeconds" placeholder="可选" clearable>
                <el-option :value="null" label="永久有效" />
                <el-option :value="3600" label="1 小时" />
                <el-option :value="86400" label="1 天" />
                <el-option :value="604800" label="7 天" />
                <el-option :value="2592000" label="30 天" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="loading" @click="handleCreate">
                生成短链
              </el-button>
            </el-form-item>
          </el-form>

          <el-divider v-if="result" />

          <div v-if="result" class="result">
            <h3>🎉 生成成功!</h3>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="短链码">
                <code>{{ result.shortCode }}</code>
              </el-descriptions-item>
              <el-descriptions-item label="完整短链">
                <div class="short-url-row">
                  <el-input v-model="result.shortUrl" readonly />
                  <el-button type="primary" :icon="DocumentCopy" @click="copyUrl(result.shortUrl)">
                    复制
                  </el-button>
                </div>
              </el-descriptions-item>
              <el-descriptions-item label="原始链接">
                <a :href="result.originalUrl" target="_blank">{{ result.originalUrl }}</a>
              </el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ result.createTime }}</el-descriptions-item>
            </el-descriptions>
            <el-button type="success" style="margin-top: 16px" @click="gotoList">
              查看所有短链
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
import copy from 'copy-to-clipboard'
import { createShortUrl } from '../api/shortUrl'

const formRef = ref()
const loading = ref(false)
const result = ref(null)

const form = reactive({
  originalUrl: '',
  expireSeconds: null
})

const rules = {
  originalUrl: [
    { required: true, message: '请输入原始链接', trigger: 'blur' },
    { max: 500, message: '链接长度不能超过500字符', trigger: 'blur' }
  ]
}

async function handleCreate() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const res = await createShortUrl({
        originalUrl: form.originalUrl,
        expireSeconds: form.expireSeconds
      })
      result.value = res.data
      ElMessage.success('短链生成成功!')
    } catch (e) {
      // 错误已在拦截器处理
    } finally {
      loading.value = false
    }
  })
}

function copyUrl(url) {
  if (copy(url)) {
    ElMessage.success('已复制到剪贴板')
  } else {
    ElMessage.error('复制失败')
  }
}

function gotoList() {
  window.open('/list', '_blank')
}
</script>

<style scoped>
.create-view {
  max-width: 900px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.result h3 {
  margin: 0 0 16px 0;
  color: #67c23a;
}
.short-url-row {
  display: flex;
  gap: 8px;
}
.short-url-row .el-input {
  flex: 1;
}
</style>