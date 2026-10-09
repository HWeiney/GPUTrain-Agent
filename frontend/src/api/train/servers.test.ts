import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import request from '@/utils/request'
import { assignServerAccount, createServer, getServer, listServers, previewHostKey, probeServer, rotateServerCredentials, testServerConnection, updateServer, listServerDevices, type ServerAccount, type ServerView, type ServerDetail } from './servers'
import { listServerGpuDevices } from './devices'
vi.mock('@/utils/request', () => ({ default: vi.fn() }))
beforeEach(() => vi.clearAllMocks())
describe('server inventory API', () => {
  it('keeps secrets out of read types', () => {
    type SecretKeys = 'credential' | 'password' | 'privateKey' | 'passphrase' | 'credentialCiphertext' | 'passphraseCiphertext'
    expectTypeOf<Extract<keyof ServerView, SecretKeys>>().toEqualTypeOf<never>()
    expectTypeOf<Extract<keyof ServerAccount, SecretKeys>>().toEqualTypeOf<never>()
    expectTypeOf<Extract<keyof ServerDetail, SecretKeys>>().toEqualTypeOf<never>()
  })
  it('maps owner scoped read and probe endpoints', () => {
    listServers(); getServer(3, 7); listServerDevices(3, 7); probeServer(3, 7)
    expect(request).toHaveBeenNthCalledWith(1, { url: '/train/servers', method: 'get' })
    expect(request).toHaveBeenNthCalledWith(2, { url: '/train/servers/3', method: 'get', params: { accountId: 7 } })
    expect(request).toHaveBeenNthCalledWith(3, { url: '/train/servers/3/devices', method: 'get', params: { accountId: 7 } })
    expect(request).toHaveBeenNthCalledWith(4, expect.objectContaining({ url: '/train/servers/3/probe', method: 'post', data: { accountId: 7 }, timeout: 90000 }))
  })
  it('previews and records explicitly confirmed host keys', () => {
    previewHostKey('gpu.local', 22)
    createServer({ name: 'GPU', host: 'gpu.local', port: 22, hostKeyFingerprint: 'SHA256:confirmed', confirmDuplicateFingerprint: false })
    updateServer(3, { hostKeyFingerprint: 'SHA256:recovered', confirmHostKeyChange: true })
    expect(request).toHaveBeenNthCalledWith(1, expect.objectContaining({ url: '/train/servers/host-key-preview', data: { host: 'gpu.local', port: 22 } }))
    expect(request).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'put', data: { hostKeyFingerprint: 'SHA256:recovered', confirmHostKeyChange: true } }))
  })
  it('disables request-body persistence for every credential submission', () => {
    const account = { ownerUserId: 1, linuxUsername: 'alice', authType: 'PASSWORD' as const, credential: 'secret', passphrase: '', allowedRoot: '/home/alice' }
    assignServerAccount(3, account)
    testServerConnection(3, { accountId: 7, credential: 'new-secret', passphrase: '' })
    rotateServerCredentials(3, 7, { rotate: true, credential: 'new-secret', passphrase: '' })
    expect(request).toHaveBeenNthCalledWith(1, expect.objectContaining({ url: '/train/servers/3/accounts', data: account, headers: { repeatSubmit: false } }))
    expect(request).toHaveBeenNthCalledWith(2, expect.objectContaining({ url: '/train/servers/3/connection-test', headers: { repeatSubmit: false }, timeout: 90000 }))
    expect(request).toHaveBeenNthCalledWith(3, expect.objectContaining({ url: '/train/servers/3/accounts/7', method: 'put', headers: { repeatSubmit: false } }))
  })
  it('passes server and own account selection to GPU catalog', () => {
    listServerGpuDevices(3, 7)
    expect(request).toHaveBeenCalledWith({ url: '/train/gpu/server-devices', method: 'get', params: { serverId: 3, accountId: 7 } })
  })
})
