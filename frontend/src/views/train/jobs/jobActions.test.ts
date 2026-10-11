import { describe, expect, it } from 'vitest'
import type { TrainJob } from '@/api/train/jobs'
import { jobAction, jobStatusText } from './jobActions'

const job = (status: string, startCommandId: number | null = null, executionMode?: string) => ({ status, startCommandId, executionMode } as TrainJob)

describe('job actions', () => {
  it('cancels queued SSH jobs only', () => {
    expect(jobAction(job('QUEUED', null, 'SSH'))).toBe('cancel')
    expect(jobAction(job('QUEUED', null, 'RUNNER_LEGACY'))).toBe('none')
  })
  it('keeps historical jobs read only', () => {
    expect(jobAction(job('RUNNING', 12, 'RUNNER_LEGACY'))).toBe('none')
  })
  it('offers stop for SSH jobs whose remote state needs a stop or reconciliation', () => {
    expect(jobAction(job('RUNNING', null, 'SSH'))).toBe('stop')
    expect(jobAction(job('UNKNOWN', null, 'SSH'))).toBe('stop')
    expect(jobAction(job('RECONCILING', null, 'SSH'))).toBe('stop')
  })
  it('disables actions during transitions and after completion', () => {
    expect(jobAction(job('ALLOCATING'))).toBe('none')
    expect(jobAction(job('STOPPING', 12))).toBe('none')
    expect(jobAction(job('STOPPED', 12))).toBe('none')
  })
  it('uses readable state text', () => {
    expect(jobStatusText('QUEUED')).toBe('排队中')
    expect(jobStatusText('STOPPING')).toBe('停止中')
    expect(jobStatusText('UNKNOWN')).toBe('状态待核对')
    expect(jobStatusText('RECONCILING')).toBe('正在核对')
  })
})
