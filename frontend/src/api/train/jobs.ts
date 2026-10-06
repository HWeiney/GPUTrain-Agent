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

export function listTrainJobs(): Promise<AjaxResult<TrainJob[]>> {
  return request({
    url: '/train/jobs',
    method: 'get'
  })
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
