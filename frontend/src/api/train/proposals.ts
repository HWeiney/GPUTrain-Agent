import request from '@/utils/request'
import type { AjaxResult } from '@/types'

function apiRequest<T>(config: Record<string, any>): Promise<AjaxResult<T>> {
  return request(config) as Promise<AjaxResult<T>>
}

export type ProposalSource = 'MANUAL' | 'AGENT'
export type ProposalStatus = 'PENDING' | 'EXECUTED' | 'REJECTED' | 'FAILED'

/** 动作参数元数据：/actions 目录返回，驱动创建对话框的动态表单。 */
export interface ProposalActionParam {
  name: string
  type: string
  required: boolean
  description: string
}

export interface ProposalActionView {
  name: string
  description: string
  params: ProposalActionParam[]
}

export interface CommandProposal {
  proposalId: string
  actorUserId: number
  serverId: number
  accountId: number
  source: ProposalSource
  rationale: string | null
  action: string
  argsJson: string
  renderedCommand: string
  status: ProposalStatus
  exitCode: number | null
  stdout: string | null
  stderr: string | null
  errorCode: string | null
  createdAt: string
  decidedAt: string | null
  executedAt: string | null
}

export interface CreateProposalPayload {
  serverId: number
  accountId: number
  action: string
  args: Record<string, unknown>
}

export interface AgentDraftPayload {
  serverId: number
  accountId: number
  intentText: string
}

/** P4.5 Agent 会话长记忆轮次：结果字段来自提案表实时 JOIN，未决或未关联提案时为 null。 */
export interface AgentMemoryTurn {
  memoryId: string
  intentText: string
  action: string
  status: ProposalStatus | null
  exitCode: number | null
  outputExcerpt: string | null
  createdAt: string
}

export function listProposals() {
  return apiRequest<CommandProposal[]>({ url: '/train/proposals', method: 'get' })
}

export function listProposalActions() {
  return apiRequest<ProposalActionView[]>({ url: '/train/proposals/actions', method: 'get' })
}

export function createProposal(data: CreateProposalPayload) {
  return apiRequest<CommandProposal>({
    url: '/train/proposals', method: 'post', data, headers: { repeatSubmit: false }
  })
}

/** P4 Agent 起草：动作目录 + 意图 → 创建 source=AGENT 的 PENDING 提案（批准才是人工确认）。 */
export function draftProposal(data: AgentDraftPayload) {
  return apiRequest<CommandProposal>({
    url: '/train/agents/proposal-draft', method: 'post', data, headers: { repeatSubmit: false }
  })
}

/** P4.5 拉取当前用户在指定服务器的最近交互记忆（时间正序，最多 8 轮），供起草对话框展示。 */
export function listAgentMemory(serverId: number) {
  return apiRequest<AgentMemoryTurn[]>({
    url: '/train/agents/memory', method: 'get', params: { serverId }
  })
}

export function approveProposal(proposalId: string) {
  return apiRequest<CommandProposal>({
    url: `/train/proposals/${proposalId}/approve`, method: 'post', headers: { repeatSubmit: false }
  })
}

export function rejectProposal(proposalId: string) {
  return apiRequest<CommandProposal>({
    url: `/train/proposals/${proposalId}/reject`, method: 'post', headers: { repeatSubmit: false }
  })
}
