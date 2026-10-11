import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import request from '@/utils/request'
import {
  approveProposal, createProposal, draftProposal, listAgentMemory, listProposalActions, listProposals, rejectProposal,
  type AgentMemoryTurn, type CommandProposal, type ProposalActionView
} from './proposals'
vi.mock('@/utils/request', () => ({ default: vi.fn() }))
beforeEach(() => vi.clearAllMocks())

describe('proposals API', () => {
  it('keeps secrets out of proposal types', () => {
    type SecretKeys = 'credential' | 'password' | 'privateKey' | 'passphrase' | 'ciphertext'
    expectTypeOf<Extract<keyof CommandProposal, SecretKeys>>().toEqualTypeOf<never>()
    expectTypeOf<Extract<keyof ProposalActionView, SecretKeys>>().toEqualTypeOf<never>()
    expectTypeOf<Extract<keyof AgentMemoryTurn, SecretKeys>>().toEqualTypeOf<never>()
    expectTypeOf<CommandProposal['rationale']>().toEqualTypeOf<string | null>()
  })
  it('lists proposals and the action catalog', () => {
    listProposals()
    expect(request).toHaveBeenCalledWith({ url: '/train/proposals', method: 'get' })
    listProposalActions()
    expect(request).toHaveBeenCalledWith({ url: '/train/proposals/actions', method: 'get' })
  })
  it('creates proposals without request-body persistence', () => {
    createProposal({ serverId: 3, accountId: 7, action: 'probe-gpu', args: {} })
    expect(request).toHaveBeenCalledWith({
      url: '/train/proposals', method: 'post',
      data: { serverId: 3, accountId: 7, action: 'probe-gpu', args: {} },
      headers: { repeatSubmit: false }
    })
  })
  it('drafts agent proposals via the guarded agent endpoint', () => {
    draftProposal({ serverId: 3, accountId: 7, intentText: '看一下系统负载' })
    expect(request).toHaveBeenCalledWith({
      url: '/train/agents/proposal-draft', method: 'post',
      data: { serverId: 3, accountId: 7, intentText: '看一下系统负载' },
      headers: { repeatSubmit: false }
    })
  })
  it('lists agent memory scoped to the selected server', () => {
    listAgentMemory(3)
    expect(request).toHaveBeenCalledWith({
      url: '/train/agents/memory', method: 'get', params: { serverId: 3 }
    })
  })
  it('decides proposals through one-shot guarded endpoints', () => {
    approveProposal('p-1')
    expect(request).toHaveBeenCalledWith({ url: '/train/proposals/p-1/approve', method: 'post', headers: { repeatSubmit: false } })
    rejectProposal('p-1')
    expect(request).toHaveBeenCalledWith({ url: '/train/proposals/p-1/reject', method: 'post', headers: { repeatSubmit: false } })
  })
})
