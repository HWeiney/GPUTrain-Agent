import { describe, expect, it } from 'vitest'
import type { ProposalActionView } from '@/api/train/proposals'
import type { ProposalStatus } from '@/api/train/proposals'
import { agentDraftReady, canDecide, collectArgs, memoryTurnSummary, paramInputType, proposalDraftReady, STATUS_LABELS, STATUS_TAGS } from './proposalForm'

const probeAction: ProposalActionView = {
  name: 'probe-gpu', description: '查询 GPU 概要', params: []
}
const jobAction: ProposalActionView = {
  name: 'job-stop', description: '停止训练任务的 systemd 用户单元',
  params: [
    { name: 'jobId', type: 'long', required: true, description: '训练任务 ID' },
    { name: 'reason', type: 'string', required: false, description: '备注（可选）' }
  ]
}

const draft = { serverId: 3, accountId: 7, action: 'job-stop' }

describe('proposal form helpers', () => {
  it('maps every proposal status to a label and a tag', () => {
    const statuses: ProposalStatus[] = ['PENDING', 'EXECUTED', 'REJECTED', 'FAILED']
    for (const status of statuses) {
      expect(STATUS_LABELS[status].length).toBeGreaterThan(0)
      expect(STATUS_TAGS[status]).toBeTruthy()
    }
  })
  it('only PENDING proposals can be approved or rejected', () => {
    expect(canDecide('PENDING')).toBe(true)
    expect(canDecide('EXECUTED')).toBe(false)
    expect(canDecide('REJECTED')).toBe(false)
    expect(canDecide('FAILED')).toBe(false)
  })
  it('renders long params as number inputs and everything else as text', () => {
    expect(paramInputType(jobAction.params[0])).toBe('number')
    expect(paramInputType(jobAction.params[1])).toBe('text')
    expect(paramInputType({ name: 'x', type: 'boolean', required: false, description: '' })).toBe('text')
  })
  it('collects filled args and skips empty or unset values', () => {
    const args = collectArgs(jobAction, { jobId: 42 }, { reason: '实验结束', note: '' })
    expect(args).toEqual({ jobId: 42, reason: '实验结束' })
    expect(collectArgs(jobAction, {}, {})).toEqual({})
  })
  it('requires server, account, action and required params before submit', () => {
    expect(proposalDraftReady(jobAction, draft, { jobId: 42 }, {})).toBe(true)
    expect(proposalDraftReady(jobAction, draft, {}, {})).toBe(false)
    expect(proposalDraftReady(jobAction, { ...draft, accountId: null }, { jobId: 42 }, {})).toBe(false)
    expect(proposalDraftReady(null, draft, {}, {})).toBe(false)
    // 可选文本参数不阻塞提交
    expect(proposalDraftReady(jobAction, draft, { jobId: 42 }, { reason: '' })).toBe(true)
  })
  it('accepts zero-param probe drafts once server and account are chosen', () => {
    expect(proposalDraftReady(probeAction, { serverId: 3, accountId: 7, action: 'probe-gpu' }, {}, {})).toBe(true)
    expect(proposalDraftReady(probeAction, { serverId: 3, accountId: null, action: 'probe-gpu' }, {}, {})).toBe(false)
  })
  it('requires server, account and non-blank intent for agent drafts', () => {
    expect(agentDraftReady(3, 7, '看一下系统负载')).toBe(true)
    expect(agentDraftReady(null, 7, '看一下系统负载')).toBe(false)
    expect(agentDraftReady(3, null, '看一下系统负载')).toBe(false)
    expect(agentDraftReady(3, 7, '   ')).toBe(false)
  })
  it('summarizes complete memory turns with exit code and excerpt', () => {
    expect(memoryTurnSummary({
      intentText: '看看磁盘占用', action: 'probe-disk', exitCode: 0,
      outputExcerpt: 'Filesystem 1K-blocks Used'
    })).toBe('看看磁盘占用 → probe-disk（exit 0）：Filesystem 1K-blocks Used')
    // 超长摘要截断到 80 字符，避免单行爆版
    expect(memoryTurnSummary({
      intentText: '看看磁盘占用', action: 'probe-disk', exitCode: 0, outputExcerpt: 'x'.repeat(100)
    })).toBe(`看看磁盘占用 → probe-disk（exit 0）：${'x'.repeat(80)}…`)
  })
  it('degrades memory turn summaries without results or excerpts', () => {
    // 无结果（提案未决或未关联）：只剩意图 → 动作
    expect(memoryTurnSummary({
      intentText: '看看磁盘占用', action: 'probe-disk', exitCode: null, outputExcerpt: null
    })).toBe('看看磁盘占用 → probe-disk')
    // 无摘要：保留退出码，不带输出；空白摘要同样省略
    expect(memoryTurnSummary({
      intentText: '看看磁盘占用', action: 'probe-disk', exitCode: 1, outputExcerpt: '   '
    })).toBe('看看磁盘占用 → probe-disk（exit 1）')
  })
  it('keeps memory turn summaries null-safe', () => {
    expect(memoryTurnSummary({
      intentText: null, action: null, exitCode: null, outputExcerpt: null
    })).toBe('（无意图） → （无动作）')
  })
})
