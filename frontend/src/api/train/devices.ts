import request from '@/utils/request'
import type { AjaxResult } from '@/types'

/**
 * Read model of a GPU device and its latest observed snapshot.
 * It is a view of facts, not authority for whether the GPU is free.
 */
export interface GpuDevice {
  gpuUuid: string
  name: string
  hostSignature: string
  identityState: string
  schedulable: boolean
  sampledAt: string | null
  totalMemoryMiB: number
  usedMemoryMiB: number
  utilizationPercent: number
  migMode: string
  hasActiveLease: boolean
  externalProcessCount: number
}

export function listGpuDevices(): Promise<AjaxResult<GpuDevice[]>> {
  return request({
    url: '/train/gpu/devices',
    method: 'get'
  })
}
