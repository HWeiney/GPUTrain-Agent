import { describe, expect, it } from 'vitest'
import type { TrainJob } from '@/api/train/jobs'
import { jobAction, jobStatusText } from './jobActions'

const job = (status: string, startCommandId: number | null = null) => ({ status, startCommandId } as TrainJob)

describe('job actions', () => {
  it('cancels jobs with no runner command', () => {
    expect(jobAction(job('PENDING'))).toBe('cancel')
    expect(jobAction(job('QUEUED'))).toBe('cancel')
  })
  it('stops only jobs with a start command', () => {
    expect(jobAction(job('STARTING', 12))).toBe('stop')
    expect(jobAction(job('RUNNING', 12))).toBe('stop')
    expect(jobAction(job('RUNNING'))).toBe('none')
  })
  it('disables actions during transitions and after completion', () => {
    expect(jobAction(job('ALLOCATING'))).toBe('none')
    expect(jobAction(job('STOPPING', 12))).toBe('none')
    expect(jobAction(job('STOPPED', 12))).toBe('none')
  })
  it('uses readable state text', () => {
    expect(jobStatusText('QUEUED')).toBe('排队中')
    expect(jobStatusText('STOPPING')).toBe('停止中')
  })
})
