<template>
  <div class="app-container job-page">
    <header class="page-header">
      <div>
        <p class="page-kicker">TRAINING JOBS</p>
        <h1>训练任务</h1>
        <p class="page-summary">提交受限 fixture，原子分配 GPU 并跟踪任务状态。</p>
      </div>
      <div class="header-actions">
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建任务</el-button>
      </div>
    </header>

    <el-table v-loading="loading" :data="jobs" border empty-text="暂无任务">
      <el-table-column label="任务" min-width="180">
        <template #default="{ row }">
          <div class="cell-id">#{{ row.jobId }}</div>
          <div class="cell-key">{{ row.requestKey }}</div>
        </template>
      </el-table-column>
      <el-table-column label="Runner" width="100">
        <template #default="{ row }">{{ row.runnerId ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="GPU 数" prop="gpuCount" width="90" />
      <el-table-column label="状态" min-width="130">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="180">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="danger"
            :disabled="isTerminal(row.status)"
            @click="stop(row)"
          >停止</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="creating" title="新建训练任务" width="560px">
      <el-form ref="formRef" :model="form" label-width="110px">
        <el-form-item label="GPU 数" required>
          <el-input-number v-model="form.gpuCount" :min="1" :max="8" />
        </el-form-item>
        <el-form-item label="单卡显存(MiB)" required>
          <el-input-number v-model="form.minMemoryMiB" :min="1024" :step="1024" />
        </el-form-item>
        <el-form-item label="工作目录" required>
          <el-input v-model="form.workingDirectory" placeholder="必须在 Runner 允许目录内" />
        </el-form-item>
        <el-form-item label="可执行文件" required>
          <el-input v-model="form.executable" />
        </el-form-item>
        <el-form-item label="参数">
          <el-input v-model="argumentsText" placeholder="空格分隔，按参数向量传递" />
        </el-form-item>
        <el-form-item label="超时(秒)">
          <el-input-number v-model="form.timeoutSeconds" :min="10" :step="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="creating = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {
  createTrainJob,
  listTrainJobs,
  stopTrainJob,
  type CreateTrainJobRequest,
  type TrainJob
} from '@/api/train/jobs'

const { proxy } = getCurrentInstance()!
const loading = ref(false)
const jobs = ref<TrainJob[]>([])
const creating = ref(false)
const submitting = ref(false)
const argumentsText = ref('')
const formRef = ref()

const TERMINAL = ['SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELED']

const form = reactive<CreateTrainJobRequest>({
  gpuCount: 1,
  minMemoryMiB: 8000,
  allowedGpuUuids: null,
  deniedGpuUuids: null,
  requestKey: '',
  workingDirectory: '/data/fixtures',
  executable: 'python3',
  arguments: [],
  environment: {},
  timeoutSeconds: 300
})

function isTerminal(status: string): boolean {
  return TERMINAL.includes(status)
}

function tagType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  switch (status) {
    case 'RUNNING': return 'success'
    case 'SUCCEEDED': return 'primary'
    case 'FAILED': return 'danger'
    case 'STOPPING': return 'warning'
    case 'QUARANTINED': return 'danger'
    default: return 'info'
  }
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date(value))
}

function openCreate() {
  form.requestKey = 'req-' + Date.now()
  argumentsText.value = ''
  creating.value = true
}

async function load() {
  loading.value = true
  try {
    const response = await listTrainJobs()
    jobs.value = response.data ?? []
  } catch {
    proxy?.$modal.msgError('任务列表加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  form.arguments = argumentsText.value.trim()
    ? argumentsText.value.trim().split(/\s+/)
    : []
  submitting.value = true
  try {
    await createTrainJob({ ...form })
    proxy?.$modal.msgSuccess('任务已提交')
    creating.value = false
    await load()
  } catch {
    proxy?.$modal.msgError('任务提交失败')
  } finally {
    submitting.value = false
  }
}

async function stop(row: TrainJob) {
  try {
    await stopTrainJob(row.jobId)
    proxy?.$modal.msgSuccess('停止指令已下发')
    await load()
  } catch {
    proxy?.$modal.msgError('停止失败')
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 18px;

  h1 { margin: 6px 0; }
}
.header-actions { display: flex; gap: 8px; }
.page-kicker { margin: 0; color: #d58a1f; font-size: 12px; letter-spacing: 0.12em; }
.page-summary { margin: 0; color: #637083; }
.cell-id { font-weight: 600; }
.cell-key { color: #909da8; font-family: "SFMono-Regular", Consolas, monospace; font-size: 12px; }
</style>
