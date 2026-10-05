import { describe, expect, it, vi } from 'vitest'
import request from '@/utils/request'
import { createTrainJob, listTrainJobs, stopTrainJob } from '@/api/train/jobs'

vi.mock('@/utils/request', () => ({ default: vi.fn() }))

describe('train jobs api', () => {
  it('requests the secured job list endpoint', () => {
    listTrainJobs()

    expect(request).toHaveBeenCalledWith({
      url: '/train/jobs',
      method: 'get'
    })
  })

  it('posts a structured job creation request', () => {
    const payload = {
      gpuCount: 1,
      minMemoryMiB: 8000,
      allowedGpuUuids: null,
      deniedGpuUuids: null,
      requestKey: 'req-1',
      workingDirectory: '/data/fixtures',
      executable: 'python3',
      arguments: ['train.py'],
      environment: {},
      timeoutSeconds: 300
    }

    createTrainJob(payload)

    expect(request).toHaveBeenCalledWith({
      url: '/train/jobs',
      method: 'post',
      data: payload
    })
  })

  it('posts an idempotent stop request', () => {
    stopTrainJob(7)

    expect(request).toHaveBeenCalledWith({
      url: '/train/jobs/7/stop',
      method: 'post'
    })
  })
})
