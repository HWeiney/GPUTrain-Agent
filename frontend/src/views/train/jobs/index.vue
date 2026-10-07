<template>
  <div class="app-container job-page">
    <header class="page-header">
      <div>
        <p class="page-kicker">TRAINING JOBS</p>
        <h1>训练任务</h1>
        <p class="page-summary">选择三张 GPU，提交固定 DocOwl2-SRFUND 短测任务，并查看运行状态。</p>
      </div>
      <div class="header-actions">
        <el-button @click="refreshJobs">刷新</el-button>
        <el-button v-hasPermi="['train:job:manage']" type="primary" @click="openCreate">新建任务</el-button>
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
          <el-tag :type="tagType(row.status)" size="small">{{ jobStatusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="180">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetails(row)">监控</el-button>
          <el-button v-if="jobAction(row) !== 'none'" v-hasPermi="['train:job:manage']"
            link type="danger" :loading="actingJobId === row.jobId" @click="act(row)">
            {{ jobAction(row) === 'cancel' ? '取消' : '停止' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="list-footer">
      <span>当前页 {{ jobs.length }} 条 · {{ listUpdatedAt }}</span>
      <div class="list-actions">
        <el-button v-if="pageCursor !== undefined" @click="backToLatest">返回最新</el-button>
        <el-button v-if="hasMore" :loading="loadingMore" @click="loadMore">下一页</el-button>
      </div>
    </div>

    <el-dialog v-model="creating" title="新建训练任务" width="min(560px, calc(100vw - 24px))">
      <el-form :model="form" label-position="top">
        <el-form-item label="训练配置"><el-input model-value="DocOwl2-SRFUND（3 卡，共享调度）" disabled /></el-form-item>
        <el-form-item label="最大步数" required>
          <el-input-number v-model="form.maxSteps" :min="1" :max="5" />
        </el-form-item>
        <el-form-item label="GPU 设备" required>
          <div v-loading="devicesLoading" class="gpu-options">
            <p class="selection-note">选择恰好 3 张 GPU。共享进程和显存仅为最新观测，提交时后端会重新检查。</p>
            <el-checkbox-group v-model="form.allowedGpuUuids">
              <div v-for="device in devices" :key="device.gpuUuid" class="gpu-option">
                <el-checkbox :value="device.gpuUuid"
                  :disabled="!deviceSelection(device).selectable || (form.allowedGpuUuids.length >= 3 && !form.allowedGpuUuids.includes(device.gpuUuid))">
                  {{ device.name }} · {{ device.gpuUuid.slice(-12) }}
                </el-checkbox>
                <small>空闲 {{ device.totalMemoryMiB - device.usedMemoryMiB }} MiB · {{ formatTime(device.sampledAt) }}
                  <span v-if="device.externalProcessCount"> · 外部进程 {{ device.externalProcessCount }}</span>
                  <span v-if="!deviceSelection(device).selectable"> · {{ deviceSelection(device).reason }}</span>
                </small>
              </div>
            </el-checkbox-group>
            <el-empty v-if="!devicesLoading && !devices.length" description="暂无 GPU 快照，请检查 Runner" :image-size="48" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="creating = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!validGpuSelection(form.allowedGpuUuids)" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailsOpen" :title="detailJob ? `任务 #${detailJob.jobId} 运行记录` : '运行记录'" size="min(900px, 92vw)" @close="stopLogRefresh">
      <template v-if="detailJob">
        <div class="job-summary">
          <el-tag :type="tagType(detailJob.status)" size="small">{{ jobStatusText(detailJob.status) }}</el-tag>
          <span>Runner {{ detailJob.runnerId ?? '-' }}</span>
          <span>{{ detailJob.gpuCount }} 张 GPU</span>
          <span>最低空闲显存 {{ detailJob.minMemoryMiB }} MiB/卡</span>
          <span>创建于 {{ formatTime(detailJob.createdAt) }}</span>
          <el-button size="small" @click="loadObservability(true)">刷新数据</el-button>
        </div>
        <el-alert v-if="monitorError" :title="monitorError" type="error" :closable="false" show-icon />
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
  cancelTrainJob,
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
import { ElMessageBox } from 'element-plus'
import { listGpuDevices, type GpuDevice } from '@/api/train/devices'
import { deviceSelection, validGpuSelection } from './jobSelection'
import { jobAction, jobStatusText } from './jobActions'
import { JobLogDecoder, loadJobLogHistory, mergeJobLogs } from './jobLogStream'

const { proxy } = getCurrentInstance()!
const loading = ref(false)
const jobs = ref<TrainJob[]>([])
const hasMore = ref(false)
const pageCursor = ref<number | undefined>()
const loadingMore = ref(false)
const listUpdatedAt = ref('尚未同步')
const actingJobId = ref<number | null>(null)
const devices = ref<GpuDevice[]>([])
const devicesLoading = ref(false)
const creating = ref(false)
const submitting = ref(false)
const detailsOpen = ref(false)
const detailJob = ref<TrainJob | null>(null)
const logs = ref<JobLogEvent[]>([])
const metrics = ref<JobMetric[]>([])
const alerts = ref<JobAlert[]>([])
const gpuResources = ref<GpuResourceMetric[]>([])
const monitorError = ref('')
const lastEventId = ref(0)
let monitorGeneration = 0
let logRefreshTimer: ReturnType<typeof setInterval> | undefined
let listRefreshTimer: ReturnType<typeof setInterval> | undefined
let logStreamController: AbortController | undefined
const logText = computed(() => logs.value.map((entry: JobLogEvent) => entry.text).join(''))

const TERMINAL = ['SUCCEEDED', 'FAILED', 'STOPPED', 'CANCELED']

const form = reactive<CreateTrainJobRequest>({
  profileId: 'docowl2-srfund',
  maxSteps: 1,
  requestKey: '',
  allowedGpuUuids: []
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

function formatTime(value: string | null): string {
  if (!value || !Number.isFinite(new Date(value).getTime())) return '暂无'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date(value))
}

function openCreate() {
  form.requestKey = 'req-' + crypto.randomUUID()
  form.maxSteps = 1
  form.allowedGpuUuids = []
  creating.value = true
  devicesLoading.value = true
  listGpuDevices().then(response => { devices.value = response.data ?? [] })
    .catch(() => { devices.value = []; proxy?.$modal.msgError('GPU 列表加载失败') })
    .finally(() => { devicesLoading.value = false })
}

async function refreshJobs() {
  if (loading.value || loadingMore.value) return
  loading.value = true
  try {
    const response = await listTrainJobs(50, pageCursor.value)
    jobs.value = response.data ?? []
    hasMore.value = jobs.value.length === 50
    listUpdatedAt.value = new Date().toLocaleTimeString('zh-CN')
  } catch {
    proxy?.$modal.msgError('任务列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  const last = jobs.value.at(-1)
  if (!last || loading.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const response = await listTrainJobs(50, last.jobId)
    pageCursor.value = last.jobId
    jobs.value = response.data ?? []
    hasMore.value = jobs.value.length === 50
    listUpdatedAt.value = new Date().toLocaleTimeString('zh-CN')
  } catch { proxy?.$modal.msgError('加载更多任务失败') }
  finally { loadingMore.value = false }
}

async function backToLatest() {
  pageCursor.value = undefined
  await refreshJobs()
}

async function submit() {
  if (!validGpuSelection(form.allowedGpuUuids)) return
  submitting.value = true
  try {
    const response = await createTrainJob({ ...form, allowedGpuUuids: [...form.allowedGpuUuids] })
    proxy?.$modal.msgSuccess('任务已提交')
    creating.value = false
    await backToLatest()
    if (response.data) await openDetails(response.data)
  } catch (error) {
    proxy?.$modal.msgError(error instanceof Error ? error.message : '任务提交失败')
  } finally {
    submitting.value = false
  }
}

async function act(row: TrainJob) {
  const action = jobAction(row)
  if (action === 'none' || actingJobId.value) return
  const label = action === 'cancel' ? '取消' : '停止'
  try {
    await ElMessageBox.confirm(`确认${label}任务 #${row.jobId}？`, `${label}训练任务`, { type: 'warning' })
  } catch { return }
  actingJobId.value = row.jobId
  try {
    if (action === 'cancel') await cancelTrainJob(row.jobId)
    else await stopTrainJob(row.jobId)
    proxy?.$modal.msgSuccess(action === 'cancel' ? '任务已取消' : '停止指令已下发，请等待任务状态更新')
    await refreshJobs()
  } catch (error) {
    proxy?.$modal.msgError(error instanceof Error ? error.message : `${label}失败`)
    await refreshJobs()
  } finally {
    actingJobId.value = null
  }
}

async function loadObservability(append = false) {
  if (!detailJob.value) return
  const jobId = detailJob.value.jobId
  const generation = monitorGeneration
  const after = append ? lastEventId.value : 0
  const [jobResult, logResult, metricResult, alertResult, resourceResult] = await Promise.allSettled([
    getTrainJob(jobId),
    loadJobLogHistory(async cursor => (await getJobLogs(jobId, cursor)).data ?? [], after),
    getJobMetrics(jobId), getJobAlerts(jobId), getJobResources(jobId)
  ])
  if (generation !== monitorGeneration || detailJob.value?.jobId !== jobId) return
  const failures: string[] = []
  if (jobResult.status === 'fulfilled') detailJob.value = jobResult.value.data ?? detailJob.value
  else failures.push('任务状态')
  if (logResult.status === 'fulfilled') {
    logs.value = mergeJobLogs(append ? logs.value : [], logResult.value)
    lastEventId.value = logs.value.at(-1)?.eventId ?? 0
  } else failures.push('日志')
  if (metricResult.status === 'fulfilled') metrics.value = metricResult.value.data ?? []
  else failures.push('指标')
  if (alertResult.status === 'fulfilled') alerts.value = alertResult.value.data ?? []
  else failures.push('告警')
  if (resourceResult.status === 'fulfilled') gpuResources.value = resourceResult.value.data ?? []
  else failures.push('GPU 资源')
  monitorError.value = failures.length ? `${failures.join('、')}加载失败，请刷新重试` : ''
}

async function loadTelemetry() {
  if (!detailJob.value) return
  const jobId = detailJob.value.jobId
  const generation = monitorGeneration
  const results = await Promise.allSettled([
    getTrainJob(jobId), getJobMetrics(jobId), getJobAlerts(jobId), getJobResources(jobId)
  ])
  if (generation !== monitorGeneration || detailJob.value?.jobId !== jobId) return
  const [jobResult, metricResult, alertResult, resourceResult] = results
  if (jobResult.status === 'fulfilled') detailJob.value = jobResult.value.data ?? detailJob.value
  if (metricResult.status === 'fulfilled') metrics.value = metricResult.value.data ?? []
  if (alertResult.status === 'fulfilled') alerts.value = alertResult.value.data ?? []
  if (resourceResult.status === 'fulfilled') gpuResources.value = resourceResult.value.data ?? []
  monitorError.value = results.some(result => result.status === 'rejected')
    ? '部分监控数据刷新失败，请重试' : ''
  if (detailJob.value && isTerminal(detailJob.value.status) && logRefreshTimer) {
    clearInterval(logRefreshTimer)
    logRefreshTimer = undefined
  }
}

async function openDetails(row: TrainJob) {
  stopLogRefresh()
  detailJob.value = row
  logs.value = []
  metrics.value = []
  alerts.value = []
  gpuResources.value = []
  lastEventId.value = 0
  monitorError.value = ''
  detailsOpen.value = true
  const generation = monitorGeneration
  try {
    await loadObservability()
    if (generation !== monitorGeneration || !detailsOpen.value || detailJob.value?.jobId !== row.jobId) return
    logRefreshTimer = setInterval(() => loadTelemetry().catch(() => undefined), 3000)
    void streamLogs(row.jobId)
  } catch {
    proxy?.$modal.msgError('运行记录加载失败')
  }
}

function stopLogRefresh() {
  monitorGeneration++
  if (logRefreshTimer) clearInterval(logRefreshTimer)
  logRefreshTimer = undefined
  logStreamController?.abort()
  logStreamController = undefined
}

async function streamLogs(jobId: number) {
  const controller = new AbortController()
  logStreamController = controller
  const generation = monitorGeneration
  const decoder = new JobLogDecoder(lastEventId.value)
  let retryMs = 1200
  while (!controller.signal.aborted && detailsOpen.value && detailJob.value?.jobId === jobId) {
    try {
      const response = await fetch(`${import.meta.env.VITE_APP_BASE_API}/train/jobs/${jobId}/logs/stream`, {
        headers: {
          Authorization: `Bearer ${getToken() ?? ''}`,
          'Last-Event-ID': String(decoder.lastEventId)
        },
        signal: controller.signal
      })
      if (response.status === 401 || response.status === 403) {
        if (generation === monitorGeneration) monitorError.value = '日志连接认证失败，请重新登录或检查权限'
        return
      }
      if (!response.ok) throw new Error('Log stream request failed')
      const reader = response.body?.getReader()
      if (!reader) throw new Error('Streaming response body unavailable')
      const textDecoder = new TextDecoder()
      while (!controller.signal.aborted) {
        const { done, value } = await reader.read()
        if (done) break
        if (generation !== monitorGeneration || detailJob.value?.jobId !== jobId) return
        for (const event of decoder.push(textDecoder.decode(value, { stream: true }))) {
          if (event.eventId > lastEventId.value) {
            logs.value = mergeJobLogs(logs.value, [event])
            lastEventId.value = event.eventId
          }
        }
      }
      retryMs = 1200
    } catch {
      if (controller.signal.aborted) return
      if (generation === monitorGeneration) monitorError.value = '日志实时连接中断，正在重连'
    }
    await new Promise((resolve) => setTimeout(resolve, retryMs))
    retryMs = Math.min(retryMs * 2, 10000)
  }
}

onMounted(() => {
  void refreshJobs()
  listRefreshTimer = setInterval(() => { void refreshJobs() }, 3000)
})
onBeforeUnmount(() => {
  stopLogRefresh()
  if (listRefreshTimer) clearInterval(listRefreshTimer)
})
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
.list-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.list-actions { display: flex; gap: 8px; }
.gpu-options { width: 100%; max-height: 330px; overflow: auto; }
.selection-note { margin: 0 0 10px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.gpu-option { padding: 9px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.gpu-option small { display: block; margin-left: 24px; color: var(--el-text-color-secondary); line-height: 1.5; }
@media (max-width: 640px) {
  .page-header { align-items: flex-start; flex-direction: column; gap: 16px; }
  .list-footer { align-items: flex-start; flex-direction: column; gap: 8px; }
}
</style>
