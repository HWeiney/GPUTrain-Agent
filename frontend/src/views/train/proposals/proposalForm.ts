import type { ProposalActionParam, ProposalActionView, ProposalStatus } from '@/api/train/proposals'

export const STATUS_LABELS: Record<ProposalStatus, string> = {
  PENDING: '待决', EXECUTED: '已执行', REJECTED: '已拒绝', FAILED: '执行失败'
}

export const STATUS_TAGS: Record<ProposalStatus, 'warning' | 'success' | 'info' | 'danger'> = {
  PENDING: 'warning', EXECUTED: 'success', REJECTED: 'info', FAILED: 'danger'
}

/** 仅 PENDING 提案可批准/拒绝；其余状态操作列只读。 */
export function canDecide(status: ProposalStatus): boolean {
  return status === 'PENDING'
}

/** 动态表单控件类型：long 用数字输入，其余按文本输入。 */
export function paramInputType(param: ProposalActionParam): 'number' | 'text' {
  return param.type === 'long' ? 'number' : 'text'
}

export interface ProposalDraft {
  serverId: number | null
  accountId: number | null
  action: string
}

export type NumberParamBucket = Record<string, number | undefined>
export type TextParamBucket = Record<string, string>

/** 从参数桶收集动作 args：long 取数字、其余取非空文本；未填字段跳过（缺失的必填项由后端拒绝）。 */
export function collectArgs(action: ProposalActionView, numbers: NumberParamBucket, texts: TextParamBucket): Record<string, unknown> {
  const args: Record<string, unknown> = {}
  for (const param of action.params) {
    if (paramInputType(param) === 'number') {
      const value = numbers[param.name]
      if (value !== undefined) args[param.name] = value
    } else {
      const value = texts[param.name]
      if (value !== undefined && value !== '') args[param.name] = value
    }
  }
  return args
}

/** 创建表单可提交：服务器/账户/动作已选，且必填参数已填。 */
export function proposalDraftReady(
  action: ProposalActionView | null,
  draft: ProposalDraft,
  numbers: NumberParamBucket,
  texts: TextParamBucket
): boolean {
  if (!action || draft.serverId === null || draft.accountId === null) return false
  return action.params.every((param) => {
    if (!param.required) return true
    if (paramInputType(param) === 'number') return numbers[param.name] !== undefined
    const value = texts[param.name]
    return value !== undefined && value !== ''
  })
}

/** Agent 起草表单可提交：服务器/账户已选且意图非空。 */
export function agentDraftReady(
  serverId: number | null,
  accountId: number | null,
  intentText: string
): boolean {
  return serverId !== null && accountId !== null && intentText.trim() !== ''
}

export interface MemoryTurnSummaryInput {
  intentText: string | null
  action: string | null
  exitCode: number | null
  outputExcerpt: string | null
}

/** 记忆轮次单行摘要：意图 → 动作（exit N）：输出摘要（超长截断）；无结果/无摘要时逐级降级，null 安全。 */
export function memoryTurnSummary(turn: MemoryTurnSummaryInput): string {
  const intent = (turn.intentText ?? '').trim() || '（无意图）'
  const action = (turn.action ?? '').trim() || '（无动作）'
  let line = `${intent} → ${action}`
  if (turn.exitCode !== null) line += `（exit ${turn.exitCode}）`
  const excerpt = (turn.outputExcerpt ?? '').trim()
  if (excerpt !== '') line += `：${excerpt.length > 80 ? excerpt.slice(0, 80) + '…' : excerpt}`
  return line
}
