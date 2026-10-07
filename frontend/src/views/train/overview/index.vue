<template>
  <div class="app-container train-overview">
    <header class="overview-header">
      <div>
        <p class="overview-kicker">SHARED GPU CONTROL PLANE</p>
        <h1>训练工作台</h1>
        <p class="overview-summary">查看控制面、实验任务与用户级 Runner 的当前状态。</p>
      </div>
      <button class="refresh-button" type="button" :disabled="loading" @click="loadOverview">
        <RefreshRight aria-hidden="true" />
        {{ loading ? '同步中' : '刷新状态' }}
      </button>
    </header>

    <div class="status-rail" :class="{ 'is-ready': !failed && overview.status === 'READY' }" role="status">
      <span class="status-pulse" aria-hidden="true"></span>
      <span class="status-label">CONTROL PLANE</span>
      <strong>{{ statusText }}</strong>
      <span class="status-note">最后同步 {{ lastUpdated }}</span>
    </div>

    <section class="overview-grid" aria-label="训练平台状态">
      <el-card v-for="card in cards" :key="card.key" class="metric-card" shadow="never">
        <el-skeleton :loading="loading" animated>
          <template #template>
            <el-skeleton-item variant="text" class="skeleton-label" />
            <el-skeleton-item variant="h1" class="skeleton-value" />
            <el-skeleton-item variant="text" class="skeleton-note" />
          </template>
          <template #default>
            <div class="metric-heading">
              <span class="metric-icon"><component :is="card.icon" /></span>
              <span>{{ card.label }}</span>
            </div>
            <div class="metric-value" :class="{ 'status-value': card.key === 'status' }">
              {{ card.value }}
            </div>
            <p>{{ card.note }}</p>
          </template>
        </el-skeleton>
      </el-card>
    </section>

    <p v-if="failed" class="load-error">
      状态加载失败。检查后端服务后，点击“刷新状态”重试。
    </p>
  </div>
</template>

<script setup lang="ts">
import { Connection, Monitor, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import { getTrainOverview, type TrainOverview } from '@/api/train/overview'

const { proxy } = getCurrentInstance()!
const loading = ref(true)
const failed = ref(false)
const lastUpdated = ref('等待首次同步')
const overview = ref<TrainOverview>({
  status: 'UNKNOWN',
  activeExperiments: 0,
  onlineRunners: 0
})

const statusText = computed(() => failed.value ? '连接失败' : overview.value.status === 'READY' ? '数据已同步' : '状态未知')
const cards = computed(() => [
  {
    key: 'status',
    label: '系统状态',
    value: statusText.value,
    note: '控制面服务与基础依赖的可用状态',
    icon: markRaw(Monitor)
  },
  {
    key: 'experiments',
    label: '运行中实验',
    value: overview.value.activeExperiments,
    note: '正在占用训练资源的实验数量',
    icon: markRaw(VideoPlay)
  },
  {
    key: 'runners',
    label: '在线 Runner',
    value: overview.value.onlineRunners,
    note: '已连接控制面的用户级执行器',
    icon: markRaw(Connection)
  }
])

async function loadOverview() {
  loading.value = true
  failed.value = false
  try {
    const response = await getTrainOverview()
    if (!response.data) {
      throw new Error('训练总览响应缺少数据')
    }
    overview.value = response.data
    lastUpdated.value = new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }).format(new Date())
  } catch (error) {
    failed.value = true
    overview.value = { status: 'UNKNOWN', activeExperiments: 0, onlineRunners: 0 }
    proxy?.$modal.msgError(error instanceof Error ? error.message : '训练总览加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadOverview)
</script>

<style scoped lang="scss">
.train-overview {
  --ink: #172234;
  --muted: #637083;
  --surface: #f3f6f8;
  --line: #dce3e8;
  --signal: #d58a1f;
  --ready: #15836d;
  min-height: calc(100vh - 84px);
  color: var(--ink);
  background:
    linear-gradient(90deg, rgba(39, 62, 81, 0.035) 1px, transparent 1px),
    var(--surface);
  background-size: 32px 32px;
}

.overview-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 34px 36px 30px;
  border: 1px solid var(--line);
  border-bottom: 0;
  background: rgba(255, 255, 255, 0.92);

  h1 {
    margin: 6px 0 8px;
    font-size: clamp(28px, 4vw, 44px);
    font-weight: 650;
    letter-spacing: -0.04em;
  }
}

.overview-kicker,
.status-label {
  margin: 0;
  color: var(--signal);
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.overview-summary {
  margin: 0;
  color: var(--muted);
  font-size: 15px;
}

.refresh-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 16px;
  border: 1px solid #aeb9c2;
  border-radius: 4px;
  color: var(--ink);
  background: #fff;
  cursor: pointer;

  svg {
    width: 17px;
  }

  &:hover:not(:disabled),
  &:focus-visible {
    border-color: var(--signal);
    outline: 2px solid rgba(213, 138, 31, 0.2);
    outline-offset: 2px;
  }

  &:disabled {
    cursor: wait;
    opacity: 0.6;
  }
}

.status-rail {
  display: grid;
  grid-template-columns: auto auto auto 1fr;
  align-items: center;
  gap: 12px;
  min-height: 54px;
  padding: 0 36px;
  border: 1px solid #273e51;
  color: #edf4f7;
  background: #273e51;

  &.is-ready .status-pulse {
    background: #57d3b7;
    box-shadow: 0 0 0 5px rgba(87, 211, 183, 0.13);
  }
}

.status-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9eabb5;
}

.status-note {
  justify-self: end;
  color: #bdc9d1;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 12px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  padding: 24px 0;
}

.metric-card {
  border: 1px solid var(--line);
  border-radius: 4px;

  :deep(.el-card__body) {
    min-height: 190px;
    padding: 26px;
  }
}

.metric-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 14px;
}

.metric-icon {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid #d9e0e5;
  border-radius: 3px;

  svg {
    width: 16px;
    color: #40586d;
  }
}

.metric-value {
  margin: 27px 0 16px;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 48px;
  line-height: 1;
  letter-spacing: -0.06em;

  &.status-value {
    color: var(--ready);
    font-size: 34px;
    letter-spacing: -0.04em;
  }
}

.metric-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
}

.skeleton-label,
.skeleton-note {
  width: 58%;
}

.skeleton-value {
  width: 42%;
  height: 48px;
  margin: 30px 0 20px;
}

.load-error {
  margin: 0;
  padding: 12px 16px;
  border-left: 3px solid #c45656;
  color: #8f3f3f;
  background: #fff2f2;
}

@media (max-width: 820px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }

  .overview-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .status-rail {
    grid-template-columns: auto 1fr;
    padding: 12px 20px;
  }

  .status-note {
    grid-column: 2;
    justify-self: start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .train-overview :deep(*) {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
  }
}
</style>
