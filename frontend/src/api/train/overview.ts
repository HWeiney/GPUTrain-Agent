import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface TrainOverview {
  status: string
  activeExperiments: number
  onlineRunners: number
}

export function getTrainOverview(): Promise<AjaxResult<TrainOverview>> {
  return request({
    url: '/train/overview',
    method: 'get'
  })
}
