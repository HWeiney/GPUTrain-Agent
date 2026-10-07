<template>
  <div class="app-container job-page">
    <header class="page-header">
      <div>
        <p class="page-kicker">TRAINING JOBS</p>
        <h1>训练任务</h1>
        <p class="page-summary">运行固定 DocOwl2-SRFUND 短测配置，自动选择显存余量足够的设备。</p>
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
          <el-button link type="primary" @click="openDetails(row)">监控</el-button>
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
        <el-form-item label="训练配置"><el-input model-value="DocOwl2-SRFUND（3 卡，共享调度）" disabled /></el-form-item>
        <el-form-item label="最大步数" required>
          <el-input-number v-model="form.maxSteps" :min="1" :max="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="creating = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailsOpen" :title="detailJob ? `任务 #${detailJob.jobId} 运行记录` : '运行记录'" size="min(900px, 92vw)" @closed="stopLogRefresh">
      <template v-if="detailJob">
        <div class="job-summary">
          <el-tag :type="tagType(detailJob.status)" size="small">{{ detailJob.status }}</el-tag>
          <span>Runner {{ detailJob.runnerId ?? '-' }}</span>
          <span>{{ detailJob.gpuCount }} 张 GPU</span>
          <span>最低空闲显存 {{ detailJob.minMemoryMiB }} MiB/卡</span>
          <span>创建于 {{ formatTime(detailJob.createdAt) }}</span>
          <el-button size="small" @click="loadObservability(true)">刷新数据</el-button>
        </div>
        <h3>告警</h3>
        <el-empty v-if="alerts.length === 0" description="暂无告警" :image-size="48" />
        <el-alert v-for="alert in alerts" :key="alert.alertId" :title="`${alert.ruleCode}: ${alert.message}`" :type="alert.severity === 'CRITICAL' ? 'error' : 'warning'" :closable="false" show-icon />
        <h3>训练指标历史</h3>
        <el-table :data="metrics" size="small" max-height="220" empty-text="尚未采集到指标">
          <el-table-column prop="name" label="指标" />
          <el-table-column prop="value" label="数值" />
          <el-table-column prop="step" label="Step" />
          <el-table-column label="时间"><template #default="{ row }">{{ formatTime(row.sampledAt) }}</template></el-table-column>
        </el-table>
        <h3>GPU 资源历史（任务租约期间最近采样）</h3>
        <el-table :data="gpuResources" size="small" empty-text="暂无任务期 GPU 采样">
          <el-table-column prop="gpuUuid" label="GPU UUID" min-width="220" />
          <el-table-column label="利用率" width="100"><template #default="{ row }">{{ row.utilizationPercent }}%</template></el-table-column>
          <el-table-column label="显存" width="180"><template #default="{ row }">{{ row.usedMemoryMiB }} / {{ row.totalMemoryMiB }} MiB</template></el-table-column>
          <el-table-column label="采样时间" min-width="170"><template #default="{ row }">{{ formatTime(row.sampledAt) }}</template></el-table-column>
        </el-table>
        <div class="log-heading"><h3>训练日志</h3><span>已读取至事件 {{ lastEventId }}</span></div>
        <pre class="job-logs" aria-live="polite">{{ logText || '暂无日志' }}</pre>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import {
  createTrainJob,
  listTrainJobs,
  stopTrainJob,
  getJobAlerts,
  getJobLogs,
  getJobMetrics,
  getJobResources,
  getTrainJob,
  type CreateTrainJobRequest,
  type JobAlert,
  type JobMetric,
  type JobLogEvent,
  type GpuResourceMetric,
  type TrainJob
} from '@/api/train/jobs'
import { getToken } from '@/utils/auth'

const { proxy } = getCurrentInstance()!
const loading = ref(false)
const jobs = ref<TrainJob[]>([])
const creating = ref(false)
const submitting = ref(false)
const formRef = ref()
const detailsOpen = ref(false)
const detailJob = ref<TrainJob | null>(null)
const logs = ref<JobLogEvent[]>([])
const metrics = ref<JobMetric[]>([])
const alerts = ref<JobAlert[]>([])
const gpuResources = ref<GpuResourceMetric[]>([])
const lastEventId = ref(0)
let logRefreshTimer: ReturnType<typeof setInterval> | undefined
let logStreamController: AbortController | undefined
const logText = computed(() => logs.value.map((entry) => entry.text).join(''))

const TERMINAL = ['SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELED']

const form = reactive<CreateTrainJobRequest>({
  profileId: 'docowl2-srfund',
  maxSteps: 1,
  requestKey: ''
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
  form.maxSteps = 1
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

async function loadObservability(append = false) {
  if (!detailJob.value) return
  const jobId = detailJob.value.jobId
  const after = append ? lastEventId.value : 0
  const [jobResult, logResult, metricResult, alertResult, resourceResult] = await Promise.all([
    getTrainJob(jobId), getJobLogs(jobId, after), getJobMetrics(jobId), getJobAlerts(jobId), getJobResources(jobId)
  ])
  detailJob.value = jobResult.data ?? detailJob.value
  const nextLogs = logResult.data ?? []
  if (append) {
    const knownIds = new Set(logs.value.map((entry) => entry.eventId))
    logs.value = [...logs.value, ...nextLogs.filter((entry) => entry.eventId > lastEventId.value && !knownIds.has(entry.eventId))]
  } else {
    logs.value = nextLogs
  }
  if (nextLogs.length) lastEventId.value = Math.max(lastEventId.value, nextLogs[nextLogs.length - 1].eventId)
  metrics.value = metricResult.data ?? []
  alerts.value = alertResult.data ?? []
  gpuResources.value = resourceResult.data ?? []
}

async function loadTelemetry() {
  if (!detailJob.value) return
  const jobId = detailJob.value.jobId
  const [jobResult, metricResult, alertResult, resourceResult] = await Promise.all([
    getTrainJob(jobId), getJobMetrics(jobId), getJobAlerts(jobId), getJobResources(jobId)
  ])
  detailJob.value = jobResult.data ?? detailJob.value
  metrics.value = metricResult.data ?? []
  alerts.value = alertResult.data ?? []
  gpuResources.value = resourceResult.data ?? []
}

async function openDetails(row: TrainJob) {
  detailJob.value = row
  logs.value = []
  metrics.value = []
  alerts.value = []
  gpuResources.value = []
  lastEventId.value = 0
  detailsOpen.value = true
  try {
    await loadObservability()
    stopLogRefresh()
    logRefreshTimer = setInterval(() => loadTelemetry().catch(() => undefined), 3000)
    void streamLogs(row.jobId)
  } catch {
    proxy?.$modal.msgError('运行记录加载失败')
  }
}

function stopLogRefresh() {
  if (logRefreshTimer) clearInterval(logRefreshTimer)
  logRefreshTimer = undefined
  logStreamController?.abort()
  logStreamController = undefined
}

async function streamLogs(jobId: number) {
  const controller = new AbortController()
  logStreamController = controller
  while (!controller.signal.aborted && detailsOpen.value && detailJob.value?.jobId === jobId) {
    try {
      const response = await fetch(`${import.meta.env.VITE_APP_BASE_API}/train/jobs/${jobId}/logs/stream`, {
        headers: {
          Authorization: `Bearer ${getToken() ?? ''}`,
          'Last-Event-ID': String(lastEventId.value)
        },
        signal: controller.signal
      })
      if (!response.ok) throw new Error('Log stream request failed')
      const reader = response.body?.getReader()
      if (!reader) throw new Error('Streaming response body unavailable')
      const decoder = new TextDecoder()
      let buffer = ''
      while (!controller.signal.aborted) {
        const { done, value } = await reader.read()
        if (done) break
        buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
        let boundary: number
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const data = frame.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n')
          if (!data) continue
          const event = JSON.parse(data) as JobLogEvent
          if (event.eventId <= lastEventId.value) continue
          logs.value.push(event)
          lastEventId.value = event.eventId
        }
      }
    } catch {
      if (controller.signal.aborted) return
    }
    await new Promise((resolve) => setTimeout(resolve, 1200))
  }
}

onMounted(load)
onBeforeUnmount(stopLogRefresh)
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 18px;

  h1 { margin: 6px 0; }
}

.job-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 20px; color: var(--el-text-color-secondary); }
.job-logs { max-height: 42vh; overflow: auto; padding: 14px; border-radius: 6px; background: #111827; color: #d1fae5; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; }
.log-heading { display: flex; align-items: center; justify-content: space-between; color: var(--el-text-color-secondary); }
.header-actions { display: flex; gap: 8px; }
.page-kicker { margin: 0; color: #d58a1f; font-size: 12px; letter-spacing: 0.12em; }
.page-summary { margin: 0; color: #637083; }
.cell-id { font-weight: 600; }
.cell-key { color: #909da8; font-family: "SFMono-Regular", Consolas, monospace; font-size: 12px; }
</style>
