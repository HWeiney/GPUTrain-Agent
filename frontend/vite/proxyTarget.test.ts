import { describe, expect, it } from 'vitest'
import { resolveControlPlaneOrigin } from './proxyTarget'

describe('resolveControlPlaneOrigin', () => {
  it('uses local backend by default', () => {
    expect(resolveControlPlaneOrigin(undefined)).toBe('http://localhost:8080')
  })

  it('accepts a remote HTTPS origin', () => {
    expect(resolveControlPlaneOrigin('https://gpu.example.test:8443')).toBe('https://gpu.example.test:8443')
  })

  it.each([
    'http://gpu.example.test',
    'https://user:secret@gpu.example.test',
    'https://gpu.example.test/api',
    'https://gpu.example.test?x=1',
    'https://gpu.example.test/#fragment'
  ])('rejects unsafe proxy target %s', (value) => {
    expect(() => resolveControlPlaneOrigin(value)).toThrow()
  })
})
