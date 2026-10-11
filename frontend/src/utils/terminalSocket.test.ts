import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TerminalSocket, bytesToBase64, encodeClientSignal, parseServerSignal, terminalSocketUrl } from './terminalSocket'

class FakeWebSocket {
  static readonly OPEN = 1
  readyState = 0
  binaryType: BinaryType = 'blob'
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor(public url: string) {}
  send(data: string) { this.sent.push(data) }
  close() { this.readyState = 3; this.onclose?.() }
  simulateOpen() { this.readyState = FakeWebSocket.OPEN; this.onopen?.() }
  receive(data: unknown) { this.onmessage?.({ data }) }
}

let socketFactoryCalls: string[] = []

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('window', {
    location: { protocol: 'https:', host: 'console.example.com' },
    setInterval, clearInterval
  })
  vi.stubGlobal('WebSocket', FakeWebSocket)
  socketFactoryCalls = []
})
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

function createSocket(callbacks: ConstructorParameters<typeof TerminalSocket>[1] = {}) {
  let fake: FakeWebSocket | undefined
  const socket = new TerminalSocket('ws://test', callbacks, (url) => {
    socketFactoryCalls.push(url)
    fake = new FakeWebSocket(url)
    return fake
  })
  return { socket, getFake: () => fake! }
}

describe('terminal socket codec', () => {
  it('encodes bytes to base64 in chunks', () => {
    expect(bytesToBase64(new TextEncoder().encode('abc'))).toBe('YWJj')
    expect(bytesToBase64(new Uint8Array(0))).toBe('')
    const utf8 = new TextEncoder().encode('终端ping')
    expect(bytesToBase64(utf8)).toBe(btoa(Array.from(utf8, (b) => String.fromCharCode(b)).join('')))
  })
  it('wraps input as a base64 TEXT signal', () => {
    expect(encodeClientSignal(new TextEncoder().encode('ping'))).toBe('{"type":"input","data":"cGluZw=="}')
  })
  it('parses known server signals and rejects malformed ones', () => {
    expect(parseServerSignal('{"type":"started","sessionUuid":"u-1"}')).toEqual({ type: 'started', sessionUuid: 'u-1' })
    expect(parseServerSignal('{"type":"error","code":"AUTH_FAILED"}')).toEqual({ type: 'error', code: 'AUTH_FAILED' })
    expect(parseServerSignal('{"type":"closed","reason":"IDLE_TIMEOUT"}')).toEqual({ type: 'closed', reason: 'IDLE_TIMEOUT' })
    expect(parseServerSignal('{"type":"heartbeat"}')).toEqual({ type: 'heartbeat' })
    expect(parseServerSignal('not-json')).toBeNull()
    expect(parseServerSignal('{"type":"started","sessionUuid":42}')).toBeNull()
    expect(parseServerSignal('{"type":"unknown"}')).toBeNull()
  })
  it('builds the handshake url from ticket and initial dimensions', () => {
    expect(terminalSocketUrl('a1b2', 120, 40)).toBe(
      'wss://console.example.com/dev-api/websocket/terminal?ticket=a1b2&cols=120&rows=40')
    expect(terminalSocketUrl('x/y+z', 80, 24)).toContain('ticket=x%2Fy%2Bz&cols=80&rows=24')
  })
})

describe('terminal socket lifecycle', () => {
  it('drops input and resize until the socket is open', () => {
    const { socket, getFake } = createSocket()
    socket.sendInput(new TextEncoder().encode('early'))
    socket.resize(100, 30)
    expect(getFake().sent).toEqual([])
    expect(socket.ready).toBe(false)
  })
  it('sends base64 input and resize frames once open', () => {
    const { socket, getFake } = createSocket()
    getFake().simulateOpen()
    socket.sendInput(new TextEncoder().encode('ls -la'))
    socket.resize(120, 40)
    expect(getFake().sent).toEqual(['{"type":"input","data":"bHMgLWxh"}', '{"type":"resize","cols":120,"rows":40}'])
    expect(socket.ready).toBe(true)
  })
  it('dispatches started, binary output and error signals', () => {
    const onStarted = vi.fn(); const onOutput = vi.fn(); const onError = vi.fn()
    const { socket, getFake } = createSocket({ onStarted, onOutput, onError })
    getFake().simulateOpen()
    getFake().receive('{"type":"started","sessionUuid":"u-9"}')
    getFake().receive(new TextEncoder().encode('SHELL-READY').slice().buffer)
    getFake().receive('{"type":"error","code":"HOST_KEY_CHANGED"}')
    expect(onStarted).toHaveBeenCalledWith('u-9')
    expect(onOutput).toHaveBeenCalledWith(new TextEncoder().encode('SHELL-READY'))
    expect(onError).toHaveBeenCalledWith('HOST_KEY_CHANGED')
  })
  it('forwards close reason exactly once and ignores later frames', () => {
    const onClosed = vi.fn()
    const { socket, getFake } = createSocket({ onClosed })
    getFake().simulateOpen()
    getFake().receive('{"type":"closed","reason":"IDLE_TIMEOUT"}')
    getFake().receive('{"type":"closed","reason":"BACKPRESSURE"}')
    getFake().receive('late-binary'.slice().length && new TextEncoder().encode('late').slice().buffer)
    expect(onClosed).toHaveBeenCalledTimes(1)
    expect(onClosed).toHaveBeenCalledWith('IDLE_TIMEOUT')
    expect(socket.ready).toBe(false)
    socket.sendInput(new TextEncoder().encode('ignored'))
    expect(getFake().sent).toEqual([])
  })
  it('falls back to SSH_CLOSED when the transport drops', () => {
    const onClosed = vi.fn()
    const { socket, getFake } = createSocket({ onClosed })
    getFake().simulateOpen()
    getFake().close()
    expect(onClosed).toHaveBeenCalledWith('SSH_CLOSED')
  })
  it('beats every 30 seconds while open and stops after close', () => {
    const { socket, getFake } = createSocket()
    getFake().simulateOpen()
    vi.advanceTimersByTime(29_999)
    expect(getFake().sent).toEqual([])
    vi.advanceTimersByTime(1)
    expect(getFake().sent).toEqual(['{"type":"heartbeat"}'])
    vi.advanceTimersByTime(30_000)
    expect(getFake().sent).toEqual(['{"type":"heartbeat"}', '{"type":"heartbeat"}'])
    socket.close()
    vi.advanceTimersByTime(60_000)
    expect(getFake().sent).toHaveLength(2)
  })
  it('close is idempotent and notifies once', () => {
    const onClosed = vi.fn()
    const { socket, getFake } = createSocket({ onClosed })
    getFake().simulateOpen()
    socket.close()
    socket.close()
    expect(onClosed).toHaveBeenCalledTimes(1)
    expect(onClosed).toHaveBeenCalledWith('SSH_CLOSED')
    expect(getFake().readyState).toBe(3)
  })
  it('uses the injected factory url', () => {
    createSocket()
    expect(socketFactoryCalls).toEqual(['ws://test'])
  })
})
