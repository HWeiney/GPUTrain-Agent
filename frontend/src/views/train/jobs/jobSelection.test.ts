import { describe, expect, it } from 'vitest'
import type { GpuDevice } from '@/api/train/devices'
import { deviceSelection, validGpuSelection } from './jobSelection'

const fresh = '2026-10-07T12:00:00Z'
const now = new Date(fresh).getTime() + 1000
const gpu = (overrides: Partial<GpuDevice> = {}): GpuDevice => ({
  gpuUuid: 'GPU-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', name: 'H20', hostSignature: 'host',
  identityState: 'OBSERVED', schedulable: true, sampledAt: fresh,
  totalMemoryMiB: 96000, usedMemoryMiB: 10000, utilizationPercent: 1,
  migMode: 'DISABLED', hasActiveLease: false, externalProcessCount: 0, ...overrides
})

describe('GPU selection', () => {
  it('accepts a fresh observed GPU reported by the backend', () => {
    expect(deviceSelection(gpu(), now)).toEqual({ selectable: true, reason: null })
  })
  it('requires three distinct UUIDs', () => {
    expect(validGpuSelection(['a', 'b', 'c'])).toBe(true)
    expect(validGpuSelection(['a', 'a', 'c'])).toBe(false)
    expect(validGpuSelection(['a', 'b'])).toBe(false)
  })

  it.each([
    [{ sampledAt: null }, '快照'],
    [{ identityState: 'UNKNOWN' }, '身份'],
    [{ hasActiveLease: true }, '租约'],
    [{ totalMemoryMiB: 80000, usedMemoryMiB: 20000 }, '显存'],
    [{ migMode: 'ENABLED' }, 'MIG']
  ] as const)('rejects invalid device: %s', (change, reason) => {
    const result = deviceSelection(gpu(change), now)
    expect(result.selectable).toBe(false)
    expect(result.reason).toContain(reason)
  })

  it('allows external occupancy when enough memory remains', () => {
    expect(deviceSelection(gpu({ externalProcessCount: 1, identityState: 'EXTERNAL_OCCUPIED', schedulable: false, utilizationPercent: 100 }), now).selectable).toBe(true)
  })
})
