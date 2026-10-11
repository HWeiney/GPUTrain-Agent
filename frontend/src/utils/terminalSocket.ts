/**
 * 终端 WebSocket 通道封装。
 *
 * 协议（与后端 TerminalMessageCodec / TerminalWebSocketHandler 对齐）：
 *  - C→S TEXT JSON：{"type":"input","data":"<base64>"} / {"type":"resize","cols":n,"rows":n} / {"type":"heartbeat"}
 *  - S→C TEXT JSON：{"type":"started","sessionUuid":...} / {"type":"error","code":...} / {"type":"closed","reason":...}
 *  - S→C BINARY：shell 输出字节流
 *
 * 浏览器 WebSocket 握手无法携带 Authorization 头，因此连接前先经 REST 取一次性票据，
 * 再以 ?ticket= 进行握手；30 秒心跳用于维持空闲会话（后端默认 30 分钟空闲回收）。
 */

export type TerminalSignal =
  | { type: 'started'; sessionUuid: string }
  | { type: 'error'; code: string }
  | { type: 'closed'; reason: string }
  | { type: 'heartbeat' }

export interface TerminalSocketCallbacks {
  onStarted?: (sessionUuid: string) => void
  onOutput?: (data: Uint8Array) => void
  onError?: (code: string) => void
  onClosed?: (reason: string) => void
}

const HEARTBEAT_INTERVAL_MS = 30_000

/** WebSocket 最小成员集：允许测试注入 fake，避免实现全部浏览器接口。 */
export type TerminalSocketLike = Pick<WebSocket, 'readyState' | 'binaryType'
  | 'onopen' | 'onmessage' | 'onclose' | 'onerror' | 'send' | 'close'>

/** 构造握手 URL：票据 + 初始终端尺寸，跟随 axios 同一套 base api 代理。 */
export function terminalSocketUrl(ticket: string, cols: number, rows: number): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const base = import.meta.env.VITE_APP_BASE_API || '/dev-api'
  return `${protocol}//${window.location.host}${base}/websocket/terminal`
    + `?ticket=${encodeURIComponent(ticket)}&cols=${cols}&rows=${rows}`
}

/** Uint8Array → base64（分块避免 String.fromCharCode 栈溢出）。 */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk)
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  return btoa(binary)
}

export function encodeClientSignal(bytes: Uint8Array): string {
  return JSON.stringify({ type: 'input', data: bytesToBase64(bytes) })
}

export function parseServerSignal(text: string): TerminalSignal | null {
  try {
    const message = JSON.parse(text)
    if (message && typeof message.type === 'string') {
      if (message.type === 'started' && typeof message.sessionUuid === 'string') return message
      if (message.type === 'error' && typeof message.code === 'string') return message
      if (message.type === 'closed' && typeof message.reason === 'string') return message
      if (message.type === 'heartbeat') return message
    }
  } catch { /* 非法信令按无处理，由调用方关闭 */ }
  return null
}

export class TerminalSocket {
  private socket: TerminalSocketLike
  private heartbeatTimer: number | undefined
  private closed = false

  constructor(
    url: string,
    private callbacks: TerminalSocketCallbacks = {},
    socketFactory: (url: string) => TerminalSocketLike = (u) => new WebSocket(u)
  ) {
    this.socket = socketFactory(url)
    this.socket.binaryType = 'arraybuffer'
    this.socket.onopen = () => { this.heartbeatTimer = window.setInterval(() => this.sendJson({ type: 'heartbeat' }), HEARTBEAT_INTERVAL_MS) }
    this.socket.onmessage = (event) => this.handleMessage(event.data)
    this.socket.onclose = () => this.finish('SSH_CLOSED')
    this.socket.onerror = () => { /* 握手失败/网络中断统一走 onclose */ }
  }

  get ready(): boolean { return this.socket.readyState === WebSocket.OPEN && !this.closed }

  sendInput(bytes: Uint8Array) { if (this.ready) this.sendJson({ type: 'input', data: bytesToBase64(bytes) }) }

  resize(cols: number, rows: number) { if (this.ready) this.sendJson({ type: 'resize', cols, rows }) }

  close() { this.finish('SSH_CLOSED'); try { this.socket.close() } catch { /* already closed */ } }

  private sendJson(payload: Record<string, unknown>) {
    try { this.socket.send(JSON.stringify(payload)) } catch { /* 发送失败由 onclose 兜底 */ }
  }

  private handleMessage(data: unknown) {
    if (typeof data === 'string') {
      const signal = parseServerSignal(data)
      if (!signal) return
      if (signal.type === 'started') this.callbacks.onStarted?.(signal.sessionUuid)
      else if (signal.type === 'error') this.callbacks.onError?.(signal.code)
      else if (signal.type === 'closed') this.finish(signal.reason)
    } else if (data instanceof ArrayBuffer) {
      this.callbacks.onOutput?.(new Uint8Array(data))
    }
  }

  /** 幂等收尾：清理心跳并通知上层；已收到 closed 信令时沿用其 reason。 */
  private finish(reason: string) {
    if (this.closed) return
    this.closed = true
    if (this.heartbeatTimer !== undefined) window.clearInterval(this.heartbeatTimer)
    this.callbacks.onClosed?.(reason)
  }
}
