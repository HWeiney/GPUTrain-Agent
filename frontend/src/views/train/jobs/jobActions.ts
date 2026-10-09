import type { TrainJob } from '@/api/train/jobs'

export type JobAction = 'cancel' | 'stop' | 'none'

const statusLabels: Record<string, string> = {
  PENDING: '待处理', QUEUED: '排队中', ALLOCATING: '分配资源中',
  ALLOCATED: '资源已分配', STARTING: '启动中', RUNNING: '运行中',
  RECONCILING: '正在核对', UNKNOWN: '状态待核对',
  SUCCEEDED: '已完成', FAILED: '失败', STOPPING: '停止中',
  STOPPED: '已停止', QUARANTINED: '已隔离', CANCELED: '已取消'
}

export function jobStatusText(status: string): string {
  return statusLabels[status] ?? status
}

export function jobAction(job: TrainJob): JobAction {
  if (job.status === 'PENDING' || job.status === 'QUEUED') return 'cancel'
  if (job.executionMode === 'SSH' && ['STARTING', 'RUNNING', 'UNKNOWN', 'RECONCILING', 'QUARANTINED'].includes(job.status)) return 'stop'
  if (job.startCommandId !== null && ['STARTING', 'RUNNING', 'QUARANTINED'].includes(job.status)) return 'stop'
  return 'none'
}
