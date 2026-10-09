import { describe, expect, it, vi } from 'vitest'
import { serverStatus, isSnapshotStale, serverPermissions, parseFacts } from './serverStatus'
import { useServerConnection } from './useServerConnection'
const fp = 'SHA256:verified'
function setup() {
  const calls: string[] = []
  const createRequests: any[] = []
  const api = {
    previewHostKey: async () => ({ data: { algorithm: 'ssh-ed25519', sha256: fp } }),
    createServer: async (data: any) => { calls.push('create'); createRequests.push(data); return { data: { serverId: 4 } } },
    testServerConnection: async () => { calls.push('test'); return { data: { success: true, errorCode: null } } },
    assignServerAccount: async () => { calls.push('save'); return { data: [] } },
    rotateServerCredentials: async () => { calls.push('rotate'); return { data: {} } }
  }
  const flow = useServerConnection(api as any)
  flow.openNew(1)
  Object.assign(flow.form, { name: 'GPU', host: 'gpu.local', linuxUsername: 'alice', allowedRoot: '/home/alice', credential: 'secret' })
  return { flow, calls, createRequests }
}
describe('server status', () => {
  it('keeps failed last snapshots offline and exposes last success', () => {
    const state = serverStatus({ status: 'OFFLINE', enabled: true, lastSuccessAt: '2026-10-07T10:00:00Z' } as any)
    expect(state.label).toBe('离线'); expect(state.lastSuccess).toContain('2026'); expect(state.current).toBe(false)
  })
  it('marks an online snapshot stale instead of keeping it green', () => {
    const state = serverStatus({ status: 'ONLINE', enabled: true, lastSuccessAt: '2026-10-07T09:59:29Z' } as any, Date.parse('2026-10-07T10:00:00Z'))
    expect(state.current).toBe(false); expect(state.label).toBe('快照过期'); expect(state.tone).toBe('warning')
  })
  it('rejects invalid, expired and implausibly future timestamps', () => {
    const now = Date.parse('2026-10-07T10:00:00Z')
    expect(isSnapshotStale('2026-10-07T09:59:29Z', now)).toBe(true)
    expect(isSnapshotStale('bad', now)).toBe(true)
    expect(isSnapshotStale('2026-10-07T10:01:01Z', now)).toBe(true)
    expect(isSnapshotStale('2026-10-07T09:59:59Z', now)).toBe(false)
  })
  it('hides administration from anonymous and non-admin users', () => {
    expect(serverPermissions('', ['admin'], ['*:*:*'])).toEqual({ view: false, manage: false })
    expect(serverPermissions(2, ['common'], ['train:server:view', 'train:server:manage'])).toEqual({ view: true, manage: false })
    expect(serverPermissions(1, ['admin'], ['*:*:*'])).toEqual({ view: true, manage: true })
  })
  it('parses only available observation facts', () => {
    expect(parseFacts('{"load1":0.5,"memoryTotalKiB":1024}').load1).toBe(0.5)
    expect(parseFacts('bad')).toEqual({})
  })
})
describe('connection wizard', () => {
  it('requires human fingerprint confirmation and connection test before save', async () => {
    const { flow, calls } = setup()
    expect(flow.canTest.value).toBe(false); expect(flow.canSave.value).toBe(false)
    await flow.preview(); expect(flow.canTest.value).toBe(false)
    flow.form.confirmed = true
    expect(flow.canTest.value).toBe(false)
    flow.confirmFingerprint(true)
    await flow.test()
    expect(calls).toEqual(['create', 'test']); expect(flow.canSave.value).toBe(true)
    await flow.save()
    expect(calls).toEqual(['create', 'test', 'save']); expect(flow.form.credential).toBe(''); expect(flow.form.passphrase).toBe('')
  })
  it('invalidates preview on address changes and test on credential changes', async () => {
    const { flow } = setup()
    await flow.preview(); flow.confirmFingerprint(true); await flow.test()
    flow.form.credential = 'changed'
    expect(flow.canSave.value).toBe(false)
    flow.form.host = 'other.local'
    expect(flow.canTest.value).toBe(false)
  })
  it('clears secrets on cancel even while a test is pending', async () => {
    const { flow } = setup()
    flow.form.passphrase = 'pass'; flow.close()
    expect(flow.form.credential).toBe(''); expect(flow.form.passphrase).toBe(''); expect(flow.visible.value).toBe(false)
  })
  it('tests existing own account before explicit credential rotation', async () => {
    const { flow, calls } = setup()
    flow.openExisting({ serverId: 4, host: 'gpu.local', port: 22, hostKeyFingerprint: fp } as any, { accountId: 8, ownerUserId: 1, linuxUsername: 'alice', authType: 'PRIVATE_KEY', allowedRoot: '/home/alice' } as any)
    flow.form.credential = 'new-key'; flow.form.passphrase = 'key-pass'
    await flow.test(); await flow.save()
    expect(calls).toEqual(['test', 'rotate']); expect(flow.form.passphrase).toBe('')
  })
  it('invalidates a preview response when the target address changes in flight', async () => {
    let finishPreview!: (value: any) => void
    const api = {
      previewHostKey: () => new Promise(resolve => { finishPreview = resolve }),
      createServer: async () => ({ data: { serverId: 41 } }),
      testServerConnection: vi.fn(async () => ({ data: { success: true } })),
      assignServerAccount: vi.fn(async () => ({ data: [] })),
      rotateServerCredentials: vi.fn()
    }
    const flow = useServerConnection(api as any)
    flow.openNew(1); flow.form.host = 'a.local'
    const pendingPreview = flow.preview()
    await Promise.resolve()
    flow.form.host = 'b.local'
    finishPreview({ data: { algorithm: 'ssh-ed25519', sha256: fp } })
    await pendingPreview
    expect(flow.previewFingerprint.value).toBe('')
    expect(flow.previewCurrent.value).toBe(false)
  })
  it('does not reuse duplicate-host confirmation for a newly previewed address', async () => {
    const { flow, calls, createRequests } = setup()
    await flow.preview(); flow.confirmFingerprint(true); flow.confirmDuplicateServer()
    flow.form.host = 'other.local'
    await flow.preview(); flow.confirmFingerprint(true)
    await flow.test()
    expect(calls).toEqual(['create', 'test'])
    expect(createRequests.at(-1).confirmDuplicateFingerprint).toBe(false)
  })
  it('allows another account on an already registered server', async () => {
    const { flow, calls } = setup()
    flow.openAccount({ serverId: 4, name: 'GPU', host: 'gpu.local', port: 22, hostKeyFingerprint: fp } as any, 9)
    Object.assign(flow.form, { linuxUsername: 'bob', allowedRoot: '/home/bob', credential: 'secret' })
    await flow.test(); await flow.save()
    expect(calls).toEqual(['test', 'save'])
  })
  it('does not send a newly opened form through an older pending server creation', async () => {
    let finishCreate!: (value: any) => void
    let createStarted!: () => void
    const started = new Promise<void>(resolve => { createStarted = resolve })
    const api = {
      previewHostKey: async () => ({ data: { algorithm: 'ssh-ed25519', sha256: fp } }),
      createServer: () => { createStarted(); return new Promise(resolve => { finishCreate = resolve }) },
      testServerConnection: vi.fn(async () => ({ data: { success: true } })),
      assignServerAccount: vi.fn(async () => ({ data: [] })),
      rotateServerCredentials: vi.fn()
    }
    const flow = useServerConnection(api as any)
    flow.openNew(1); Object.assign(flow.form, { name: 'A', host: 'a.local', linuxUsername: 'alice', allowedRoot: '/home/alice', credential: 'secret-A' })
    await flow.preview(); flow.confirmFingerprint(true)
    const pendingTest = flow.test(); await started
    flow.close()
    flow.openNew(2); Object.assign(flow.form, { name: 'B', host: 'b.local', linuxUsername: 'bob', allowedRoot: '/home/bob', credential: 'secret-B' })
    await flow.preview(); flow.confirmFingerprint(true)
    finishCreate({ data: { serverId: 41 } })
    await pendingTest
    expect(api.testServerConnection).not.toHaveBeenCalled()
    expect(flow.form.credential).toBe('secret-B')
  })
  it('invalidates a successful test if credentials change while SSH is pending', async () => {
    let finishTest!: (value: any) => void
    const api = {
      previewHostKey: async () => ({ data: { algorithm: 'ssh-ed25519', sha256: fp } }),
      createServer: async () => ({ data: { serverId: 41 } }),
      testServerConnection: () => new Promise(resolve => { finishTest = resolve }),
      assignServerAccount: vi.fn(async () => ({ data: [] })),
      rotateServerCredentials: vi.fn()
    }
    const flow = useServerConnection(api as any)
    flow.openNew(1); Object.assign(flow.form, { name: 'A', host: 'a.local', linuxUsername: 'alice', allowedRoot: '/home/alice', credential: 'secret-A' })
    await flow.preview(); flow.confirmFingerprint(true)
    const pendingTest = flow.test()
    await Promise.resolve()
    flow.form.credential = 'secret-B'
    flow.confirmFingerprint(false)
    finishTest({ data: { success: true } })
    await pendingTest
    expect(flow.canSave.value).toBe(false)
  })
})
