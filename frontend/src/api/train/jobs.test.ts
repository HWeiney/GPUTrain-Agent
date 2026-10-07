import { describe, expect, it, vi } from 'vitest'
import request from '@/utils/request'
import { cancelTrainJob, createTrainJob, getJobAlerts, getJobLogs, getJobMetrics, getJobResources, getTrainJob, listTrainJobs, stopTrainJob } from '@/api/train/jobs'

vi.mock('@/utils/request', () => ({ default: vi.fn() }))

describe('train jobs api', () => {
  it('requests the secured job list endpoint', () => {
    listTrainJobs(50)

    expect(request).toHaveBeenCalledWith({
      url: '/train/jobs',
      method: 'get',
      params: { limit: 50 }
    })
  })

  it('posts a structured job creation request', () => {
    const payload = {
      profileId: 'docowl2-srfund' as const,
      maxSteps: 1,
      allowedGpuUuids: ['GPU-a', 'GPU-b', 'GPU-c'],
      requestKey: 'req-1',
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

  it('posts a cancel request for a queued job', () => {
    cancelTrainJob(7)
    expect(request).toHaveBeenCalledWith({ url: '/train/jobs/7/cancel', method: 'post' })
  })

  it('requests resumable log history from the last event ID', () => {
    getJobLogs(7, 12)
    expect(request).toHaveBeenCalledWith({
      url: '/train/jobs/7/logs', method: 'get', params: { afterEventId: 12, limit: 500 }
    })
  })

  it('refreshes a single job status', () => {
    getTrainJob(7)
    expect(request).toHaveBeenCalledWith({ url: '/train/jobs/7', method: 'get' })
  })

  it('loads job metrics and deterministic alerts', () => {
    getJobMetrics(7)
    getJobAlerts(7)
    getJobResources(7)
    expect(request).toHaveBeenNthCalledWith(1, { url: '/train/jobs/7/metrics', method: 'get' })
    expect(request).toHaveBeenNthCalledWith(2, { url: '/train/jobs/7/alerts', method: 'get' })
    expect(request).toHaveBeenNthCalledWith(3, { url: '/train/jobs/7/resources', method: 'get' })
  })
})
