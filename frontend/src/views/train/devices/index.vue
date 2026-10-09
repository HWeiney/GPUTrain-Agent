<template>
  <div class="app-container device-page">
    <header class="page-header">
      <div>
        <p class="page-kicker">GPU DEVICE CATALOG</p>
        <h1>GPU 设备</h1>
        <p class="page-summary">Runner 上报的 GPU 快照、身份状态与外部占用。数据仅为观测事实，不代表可调度。</p>
      </div>
      <div class="header-actions">
        <el-select v-model="source" aria-label="GPU 数据来源" class="source-select" @change="load">
          <el-option label="Runner 设备" value="runner" />
          <el-option v-for="item in serverOptions" :key="item.key" :label="item.label" :value="item.key" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <el-table v-loading="loading" :data="devices" border class="device-table" empty-text="暂无已上报设备">
      <el-table-column label="GPU" min-width="220">
        <template #default="{ row }">
          <div class="cell-name">{{ row.name }}</div>
          <div class="cell-uuid">{{ maskUuid(row.gpuUuid) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="主机" prop="hostSignature" min-width="150" />
      <el-table-column label="显存 (已用 / 总量)" min-width="170">
        <template #default="{ row }">
          {{ row.usedMemoryMiB }} / {{ row.totalMemoryMiB }} MiB
        </template>
      </el-table-column>
      <el-table-column label="利用率" prop="utilizationPercent" width="90">
        <template #default="{ row }">{{ row.utilizationPercent }}%</template>
      </el-table-column>
      <el-table-column label="MIG" prop="migMode" width="110" />
      <el-table-column label="外部进程" prop="externalProcessCount" width="90" />
      <el-table-column label="状态" min-width="150">
        <template #default="{ row }">
          <el-tag :type="tagType(row)" size="small">{{ statusText(row) }}</el-tag>
          <div v-if="isStale(row)" class="stale-note">快照过期</div>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="failed" class="load-error">设备列表加载失败，请检查后端服务后重试。</p>
  </div>
</template>

<script setup lang="ts">
import { listGpuDevices, listServerGpuDevices, type GpuDevice } from '@/api/train/devices'
import { listServers, type ServerEntry } from '@/api/train/servers'
import { isSnapshotStale } from '@/views/train/servers/serverStatus'
import useUserStore from '@/store/modules/user'

const { proxy } = getCurrentInstance()!
const userStore = useUserStore()
const loading = ref(false)
const failed = ref(false)
const now = ref(Date.now())
const devices = ref<GpuDevice[]>([])
const source = ref('runner')
const displayedSource = ref('runner')
const serverEntries = ref<ServerEntry[]>([])
const serverOptions = computed(() => (serverEntries.value ?? []).flatMap((entry: ServerEntry) => entry.accounts.map((account) => ({
  key: `${entry.server.serverId}:${account.accountId}`,
  label: `${entry.server.name} · ${account.linuxUsername}`
}))))

function isStale(row: GpuDevice): boolean {
  if (!row.sampledAt) return true
  return isSnapshotStale(row.sampledAt, now.value)
}

function statusText(row: GpuDevice): string {
  if (row.hasActiveLease) return '已租'
  if (row.identityState !== 'CONFIRMED') return row.identityState
  if (isStale(row)) return '快照过期'
  if (row.externalProcessCount > 0) return '外部占用'
  if (row.schedulable) return '可调度'
  return '不可调度'
}

function tagType(row: GpuDevice): 'success' | 'warning' | 'danger' | 'info' {
  if (row.hasActiveLease) return 'success'
  if (row.identityState !== 'CONFIRMED' || isStale(row)) return 'danger'
  if (row.externalProcessCount > 0) return 'warning'
  return row.schedulable ? 'success' : 'info'
}

function maskUuid(uuid: string): string {
  return uuid.length > 14 ? uuid.slice(0, 10) + '…' + uuid.slice(-4) : uuid
}

let loadGeneration = 0
async function load() {
  const generation = ++loadGeneration
  const requestedSource = source.value
  if (requestedSource !== displayedSource.value) {
    devices.value = []
    displayedSource.value = requestedSource
  }
  loading.value = true
  failed.value = false
  try {
    if (userStore.permissions.includes('train:server:view') || userStore.roles.includes('admin')) {
      try {
        const response = await listServers()
        if (generation !== loadGeneration || requestedSource !== source.value) return
        serverEntries.value = response.data ?? []
      } catch { if (generation === loadGeneration && requestedSource === source.value) serverEntries.value = [] }
    } else serverEntries.value = []
    if (generation !== loadGeneration || requestedSource !== source.value) return
    if (source.value !== 'runner' && !serverOptions.value.some((option: { key: string; label: string }) => option.key === source.value)) source.value = 'runner'
    const effectiveSource = source.value
    if (effectiveSource !== displayedSource.value) {
      devices.value = []
      displayedSource.value = effectiveSource
    }
    if (effectiveSource === 'runner') {
      const response = await listGpuDevices()
      if (generation !== loadGeneration || effectiveSource !== source.value) return
      devices.value = response.data ?? []
    } else {
      const [serverIdText, accountIdText] = effectiveSource.split(':')
      const response = await listServerGpuDevices(Number(serverIdText), Number(accountIdText))
      if (generation !== loadGeneration || effectiveSource !== source.value) return
      devices.value = (response.data ?? []).map(device => ({
        gpuUuid: device.gpuUuid,
        name: device.name,
        hostSignature: serverEntries.value?.find((entry: ServerEntry) => entry.server.serverId === device.serverId)?.server.host ?? String(device.serverId),
        identityState: device.identityState === 'OBSERVED' ? 'CONFIRMED' : device.identityState,
        schedulable: device.schedulable,
        sampledAt: device.sampledAt,
        totalMemoryMiB: device.totalMemoryMiB,
        usedMemoryMiB: device.usedMemoryMiB,
        utilizationPercent: device.utilizationPercent,
        migMode: device.migMode,
        hasActiveLease: false,
        externalProcessCount: device.externalProcessCount
      }))
    }
  } catch (error) {
    if (generation === loadGeneration && requestedSource === source.value) {
      failed.value = true
      proxy?.$modal.msgError('设备列表加载失败')
    }
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

let freshnessTimer: number | undefined
onMounted(() => { load(); freshnessTimer = window.setInterval(() => { now.value = Date.now() }, 5_000) })
onBeforeUnmount(() => { loadGeneration++; if (freshnessTimer !== undefined) window.clearInterval(freshnessTimer) })
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 18px;

  h1 {
    margin: 6px 0;
  }
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.source-select {
  min-width: 220px;
}
.page-kicker {
  margin: 0;
  color: #d58a1f;
  font-size: 12px;
  letter-spacing: 0.12em;
}
.page-summary {
  margin: 0;
  color: #637083;
}
.cell-name {
  font-weight: 600;
}
.cell-uuid {
  color: #909da8;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
}
.stale-note {
  margin-top: 4px;
  color: #c45656;
  font-size: 12px;
}
.load-error {
  margin-top: 14px;
  color: #8f3f3f;
}
@media (max-width: 640px) {
  .page-header, .header-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .source-select { width: 100%; }
}
</style>
