import { describe, expect, it, vi } from 'vitest'
import request from '@/utils/request'
import { getTrainOverview } from '@/api/train/overview'

vi.mock('@/utils/request', () => ({ default: vi.fn() }))

describe('getTrainOverview', () => {
  it('requests the secured overview endpoint', () => {
    getTrainOverview()

    expect(request).toHaveBeenCalledWith({
      url: '/train/overview',
      method: 'get'
    })
  })
})
