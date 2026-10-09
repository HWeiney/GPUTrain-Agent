import type { ServerView } from '@/api/train/servers'

export interface ServerStatusView {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'info'
  current: boolean
  lastSuccess: string
}

const statusLabels: Record<string, string> = {
  ONLINE: '在线', DEGRADED: '部分可用', OFFLINE: '离线',
  HOST_KEY_CHANGED: '主机密钥已变化', AUTH_FAILED: '认证失败', UNKNOWN: '未探测'
}

export function isSnapshotStale(value?: string | null, now = Date.now()): boolean {
  if (!value) return true
  const sampled = Date.parse(value)
  return !Number.isFinite(sampled) || now - sampled > 30_000 || sampled - now > 60_000
}

export function formatSampleTime(value?: string | null): string {
  if (!value || !Number.isFinite(Date.parse(value))) return '暂无成功采样'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value))
}

export function serverStatus(server: Pick<ServerView, 'status' | 'enabled' | 'lastSuccessAt'>, now = Date.now()): ServerStatusView {
  const staleOnline = server.enabled && server.status === 'ONLINE' && isSnapshotStale(server.lastSuccessAt, now)
  const current = server.enabled && server.status === 'ONLINE' && !staleOnline
  const status = server.enabled ? server.status : 'OFFLINE'
  const tone = staleOnline ? 'warning' : status === 'ONLINE' ? 'success' : status === 'DEGRADED' ? 'warning'
    : status === 'OFFLINE' || status === 'HOST_KEY_CHANGED' || status === 'AUTH_FAILED' ? 'danger' : 'info'
  return {
    label: staleOnline ? '快照过期' : statusLabels[status] ?? status,
    tone,
    current,
    lastSuccess: formatSampleTime(server.lastSuccessAt)
  }
}

export function parseFacts(value?: string | null): Record<string, number> {
  if (!value || value.length > 65_536) return {}
  try {
    const parsed: unknown = JSON.parse(value)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return Object.fromEntries(Object.entries(parsed).filter(([, item]) => typeof item === 'number' && Number.isFinite(item)))
  } catch {
    return {}
  }
}

export function serverPermissions(userId: number | string | null, roles: string[], permissions: string[]) {
  const authenticated = userId !== null && userId !== ''
  const admin = authenticated && roles.includes('admin')
  const allPermissions = permissions.includes('*:*:*')
  return {
    view: authenticated && (admin || permissions.includes('train:server:view') || allPermissions),
    manage: authenticated && admin && (permissions.includes('train:server:manage') || allPermissions)
  }
}
