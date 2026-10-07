import request from '@/utils/request'
import type { AjaxResult } from '@/types'

/** Minimal training job view used by the job list and creation flow. */
export interface TrainJob {
  jobId: number
  jobUuid: string
  ownerUserId: number
  runnerId: number | null
  gpuCount: number
  minMemoryMiB: number
  status: string
  requestKey: string
  startCommandId: number | null
  stopCommandId: number | null
  createdAt: string
  updatedAt: string
}

export interface CreateTrainJobRequest {
  profileId: 'docowl2-srfund'
  maxSteps: number
  requestKey: string
}

export interface JobLogEvent {
  eventId: number
  stream: 'STDOUT' | 'STDERR'
  offset: number
  nextOffset: number
  text: string
  createdAt: string
}

export interface JobMetric {
  metricId: number
  name: string
  value: number
  step: number | null
  sampledAt: string
}

export interface JobAlert {
  alertId: number
  ruleCode: string
  severity: string
  message: string
  createdAt: string
}

export interface GpuResourceMetric {
  gpuUuid: string
  utilizationPercent: number
  totalMemoryMiB: number
  usedMemoryMiB: number
  sampledAt: string
}

export function listTrainJobs(): Promise<AjaxResult<TrainJob[]>> {
  return request({
    url: '/train/jobs',
    method: 'get'
  })
}

export function getTrainJob(jobId: number): Promise<AjaxResult<TrainJob>> {
  return request({ url: `/train/jobs/${jobId}`, method: 'get' })
}

export function createTrainJob(
  data: CreateTrainJobRequest
): Promise<AjaxResult<TrainJob>> {
  return request({
    url: '/train/jobs',
    method: 'post',
    data
  })
}

export function stopTrainJob(jobId: number): Promise<AjaxResult<TrainJob>> {
  return request({
    url: `/train/jobs/${jobId}/stop`,
    method: 'post'
  })
}

export function getJobLogs(jobId: number, afterEventId = 0): Promise<AjaxResult<JobLogEvent[]>> {
  return request({ url: `/train/jobs/${jobId}/logs`, method: 'get', params: { afterEventId, limit: 500 } })
}

export function getJobMetrics(jobId: number): Promise<AjaxResult<JobMetric[]>> {
  return request({ url: `/train/jobs/${jobId}/metrics`, method: 'get' })
}

export function getJobAlerts(jobId: number): Promise<AjaxResult<JobAlert[]>> {
  return request({ url: `/train/jobs/${jobId}/alerts`, method: 'get' })
}

export function getJobResources(jobId: number): Promise<AjaxResult<GpuResourceMetric[]>> {
  return request({ url: `/train/jobs/${jobId}/resources`, method: 'get' })
}
