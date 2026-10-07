import type { GpuDevice } from '@/api/train/devices'

export interface JobSelection {
  selectable: boolean
  reason: string | null
}

export function validGpuSelection(uuids: string[]): boolean {
  return uuids.length === 3 && new Set(uuids).size === 3 && uuids.every(Boolean)
}

export function deviceSelection(device: GpuDevice, now = Date.now()): JobSelection {
  const sampled = device.sampledAt ? new Date(device.sampledAt).getTime() : NaN
  if (!Number.isFinite(sampled) || now - sampled > 30_000 || sampled > now + 5_000)
    return { selectable: false, reason: 'GPU 快照缺失或过期' }
  if (device.gpuUuid.startsWith('MIG-') || device.migMode.toUpperCase() === 'ENABLED')
    return { selectable: false, reason: '暂不支持 MIG 设备' }
  if (!['OBSERVED', 'EXTERNAL_OCCUPIED'].includes(device.identityState))
    return { selectable: false, reason: 'GPU 身份未确认' }
  if (device.hasActiveLease) return { selectable: false, reason: '已有活跃租约' }
  if (device.totalMemoryMiB - device.usedMemoryMiB < 70_000)
    return { selectable: false, reason: '空闲显存低于 70,000 MiB' }
  if (!device.schedulable && device.identityState !== 'EXTERNAL_OCCUPIED')
    return { selectable: false, reason: '当前不可调度' }
  return { selectable: true, reason: null }
}
