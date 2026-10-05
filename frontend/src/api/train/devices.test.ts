import { describe, expect, it, vi } from 'vitest'
import request from '@/utils/request'
import { listGpuDevices } from '@/api/train/devices'

vi.mock('@/utils/request', () => ({ default: vi.fn() }))

describe('listGpuDevices', () => {
  it('requests the secured device list endpoint', () => {
    listGpuDevices()

    expect(request).toHaveBeenCalledWith({
      url: '/train/gpu/devices',
      method: 'get'
    })
  })
})
