import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import request from '@/utils/request'
import { fetchTerminalTicket, listTerminalConnections, type TerminalTicket } from './terminal'
vi.mock('@/utils/request', () => ({ default: vi.fn() }))
beforeEach(() => vi.clearAllMocks())

describe('terminal API', () => {
  it('keeps secrets out of ticket types', () => {
    type SecretKeys = 'credential' | 'password' | 'privateKey' | 'passphrase' | 'ciphertext'
    expectTypeOf<Extract<keyof TerminalTicket, SecretKeys>>().toEqualTypeOf<never>()
  })
  it('issues one-shot tickets without request-body persistence', () => {
    fetchTerminalTicket(3, 7)
    expect(request).toHaveBeenCalledWith({
      url: '/train/terminal/ticket', method: 'post', data: { serverId: 3, accountId: 7 },
      headers: { repeatSubmit: false }
    })
  })
  it('lists connectable servers through the terminal scope', () => {
    listTerminalConnections()
    expect(request).toHaveBeenCalledWith({ url: '/train/terminal/connections', method: 'get' })
  })
})
