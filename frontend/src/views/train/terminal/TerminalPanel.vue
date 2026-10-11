<template>
  <div class="terminal-panel">
    <div class="panel-bar">
      <div class="panel-title">
        <span class="title-text">{{ title }}</span>
        <el-tag size="small" :type="phaseTone" effect="light">{{ phaseLabel }}</el-tag>
      </div>
      <el-button size="small" link type="primary" :disabled="phase === 'closed'" @click="closeSession">关闭会话</el-button>
    </div>
    <div class="terminal-body">
      <div ref="container" class="xterm-holder" />
      <div v-if="phase === 'connecting'" class="overlay">正在建立 SSH 连接…</div>
      <div v-else-if="phase === 'error'" class="overlay overlay-error">{{ errorText }}</div>
      <div v-else-if="phase === 'closed'" class="overlay">会话已结束（{{ closeReasonLabel }}）</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { fetchTerminalTicket } from '@/api/train/terminal'
import { TerminalSocket, terminalSocketUrl } from '@/utils/terminalSocket'

const props = defineProps<{ serverId: number; accountId: number; title: string }>()
const emit = defineEmits<{ (e: 'session-closed', reason: string): void }>()

const container = ref<HTMLDivElement>()
const phase = ref<'connecting' | 'open' | 'error' | 'closed'>('connecting')
const errorCode = ref('')
const closeReason = ref('')

let terminal: Terminal | undefined
let fitAddon: FitAddon | undefined
let socket: TerminalSocket | undefined
let resizeObserver: ResizeObserver | undefined

const PHASE_LABELS: Record<string, string> = { connecting: '连接中', open: '已连接', error: '连接失败', closed: '已关闭' }
const phaseLabel = computed(() => PHASE_LABELS[phase.value] ?? phase.value)
const phaseTone = computed(() => (phase.value === 'open' ? 'success' : phase.value === 'connecting' ? 'info' : phase.value === 'error' ? 'danger' : 'info'))
const closeReasonLabel = computed(() => REASON_TEXTS[closeReason.value] ?? closeReason.value)
const errorText = computed(() => REASON_TEXTS[errorCode.value] ?? '连接失败，请稍后重试')

const REASON_TEXTS: Record<string, string> = {
  TICKET_INVALID: '连接票据无效或已过期，请重新发起',
  ACCOUNT_INVALID: '服务器账号不可用',
  SESSION_LIMIT: '终端会话数已达上限',
  CONNECT_TIMEOUT: '连接服务器超时',
  CONNECT_FAILED: '无法连接到服务器',
  AUTH_TIMEOUT: '认证超时',
  AUTH_FAILED: '认证失败，请检查账号凭据',
  HOST_KEY_CHANGED: '服务器主机密钥已变化，连接被拒绝',
  IDLE_TIMEOUT: '会话空闲超时，已自动断开',
  MAX_DURATION: '会话达到最大时长，已自动断开',
  BACKPRESSURE: '客户端持续未响应，会话已断开',
  PROTOCOL: '协议信令错误，连接已关闭',
  SSH_CLOSED: '远程会话已关闭',
  INTERNAL: '内部错误'
}

onMounted(async () => {
  const holder = container.value
  if (!holder) return
  terminal = new Terminal({
    fontSize: 13,
    cursorBlink: true,
    scrollback: 5000,
    convertEol: false,
    theme: { background: '#101822' }
  })
  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.open(holder)
  try { fitAddon.fit() } catch { /* 布局未就绪时用默认尺寸 */ }
  terminal.onData((data) => socket?.sendInput(new TextEncoder().encode(data)))

  resizeObserver = new ResizeObserver(() => {
    if (!fitAddon || !terminal) return
    try { fitAddon.fit() } catch { return }
    if (socket?.ready) socket.resize(terminal.cols, terminal.rows)
  })
  resizeObserver.observe(holder)

  try {
    const response = await fetchTerminalTicket(props.serverId, props.accountId)
    const ticket = response.data?.ticket
    if (!ticket) throw new Error('TICKET_INVALID')
    socket = new TerminalSocket(terminalSocketUrl(ticket, terminal.cols, terminal.rows), {
      onStarted: () => { phase.value = 'open'; if (socket?.ready) socket.resize(terminal!.cols, terminal!.rows) },
      onOutput: (data) => terminal?.write(data),
      onError: (code) => { errorCode.value = code; phase.value = 'error' },
      onClosed: (reason) => { closeReason.value = reason; phase.value = 'closed'; emit('session-closed', reason) }
    })
  } catch {
    errorCode.value = 'TICKET_INVALID'
    phase.value = 'error'
  }
})

function closeSession() {
  socket?.close()
}

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  socket?.close()
  terminal?.dispose()
})
</script>

<style scoped lang="scss">
.terminal-panel { display: flex; flex-direction: column; min-height: 0; height: 100%; background: #101822; }
.panel-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 14px; background: #172234; }
.panel-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.title-text { overflow: hidden; color: #dce6f0; font: 600 13px/1.4 "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.terminal-body { position: relative; flex: 1; min-height: 0; padding: 6px 8px; }
.xterm-holder { width: 100%; height: 100%; }
.overlay { position: absolute; inset: 0; display: grid; place-items: center; color: #9fb2c6; font: 500 13px/1.6 inherit; text-align: center; background: rgb(16 24 34 / 82%); }
.overlay-error { color: #ff9d7a; }
</style>
