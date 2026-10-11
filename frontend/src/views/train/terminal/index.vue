<template>
  <div class="app-container terminal-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">GPU TRAIN / TERMINAL</p>
        <h1>远程终端</h1>
        <p class="intro">在浏览器中打开服务器 shell；会话全程审计留痕，空闲 30 分钟自动断开。</p>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" @click="load">刷新连接</el-button>
      </div>
    </header>

    <el-alert v-if="loadError" title="可连接清单加载失败" type="error" :closable="false" show-icon />

    <div class="terminal-layout">
      <aside class="server-list">
        <el-table v-loading="loading" :data="entries" row-key="server.serverId" border empty-text="暂无可连接的服务器">
          <el-table-column label="服务器 / 账户" min-width="190">
            <template #default="{ row }">
              <div class="server-cell">
                <span class="server-name">{{ row.server.name }}</span>
                <span class="host-address">{{ row.server.host }}:{{ row.server.port }}</span>
                <div class="account-buttons">
                  <el-button v-for="account in connectableAccounts(row)" :key="account.accountId"
                    size="small" :type="sessionKey(row.server, account) === activeKey ? 'primary' : 'default'"
                    :disabled="hasSession(row.server, account)" @click="openSession(row.server, account)">
                    {{ account.linuxUsername }}
                  </el-button>
                  <span v-if="!connectableAccounts(row).length" class="muted">暂无可用账户</span>
                </div>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </aside>

      <section class="terminal-workspace">
        <el-empty v-if="!sessions.length" description="从左侧选择服务器账户，开始终端会话" />
        <el-tabs v-else v-model="activeKey" type="card" closable @tab-remove="removeSession">
          <el-tab-pane v-for="session in sessions" :key="session.key" :name="session.key" :label="session.label" lazy>
            <TerminalPanel :server-id="session.serverId" :account-id="session.accountId" :title="session.label" />
          </el-tab-pane>
        </el-tabs>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { listTerminalConnections } from '@/api/train/terminal'
import type { ServerAccount, ServerEntry, ServerView } from '@/api/train/servers'
import TerminalPanel from './TerminalPanel.vue'

/** 与后端 gputrain.terminal.max-sessions-per-user 默认值保持一致。 */
const FRONTEND_SESSION_LIMIT = 3

interface TerminalTabSession {
  key: string
  label: string
  serverId: number
  accountId: number
}

const { proxy } = getCurrentInstance()!
const entries = ref<ServerEntry[]>([])
const loading = ref(false)
const loadError = ref(false)
const activeKey = ref('')
const sessions = ref<TerminalTabSession[]>([])

let loadGeneration = 0
async function load() {
  const generation = ++loadGeneration
  loading.value = true; loadError.value = false
  try {
    const response = await listTerminalConnections()
    if (generation === loadGeneration) entries.value = response.data ?? []
  } catch {
    if (generation === loadGeneration) { loadError.value = true; proxy?.$modal.msgError('可连接清单加载失败') }
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

function connectableAccounts(entry: ServerEntry): ServerAccount[] {
  if (!entry.server.enabled) return []
  return entry.accounts.filter((account) => account.hasCredential)
}

function sessionKey(server: ServerView, account: ServerAccount): string {
  return `${server.serverId}:${account.accountId}`
}

function hasSession(server: ServerView, account: ServerAccount): boolean {
  return sessions.value.some((session: TerminalTabSession) => session.key === sessionKey(server, account))
}

function openSession(server: ServerView, account: ServerAccount) {
  const key = sessionKey(server, account)
  if (hasSession(server, account)) { activeKey.value = key; return }
  if (sessions.value.length >= FRONTEND_SESSION_LIMIT) {
    proxy?.$modal.msgError(`同时最多 ${FRONTEND_SESSION_LIMIT} 个终端会话，请先关闭空闲会话`)
    return
  }
  sessions.value.push({ key, label: `${server.name} / ${account.linuxUsername}`, serverId: server.serverId, accountId: account.accountId })
  activeKey.value = key
}

function removeSession(targetKey: string | number) {
  const key = String(targetKey)
  sessions.value = sessions.value.filter((session: TerminalTabSession) => session.key !== key)
  if (activeKey.value === key) activeKey.value = sessions.value[sessions.value.length - 1]?.key ?? ''
}

onMounted(load)
onBeforeUnmount(() => { loadGeneration++ })
</script>

<style scoped lang="scss">
.terminal-page { --ink:#172234; --muted:#647386; --line:#dbe3e9; --signal:#d58a1f; color:var(--ink); }
.page-header { display:flex; align-items:flex-end; justify-content:space-between; gap:22px; margin-bottom:22px; }
.eyebrow { margin:0; color:var(--signal); font:700 11px/1.3 "SFMono-Regular",Consolas,monospace; letter-spacing:.14em; }
h1 { margin:7px 0 6px; font-size:30px; letter-spacing:-.035em; }
.intro { margin:0; color:var(--muted); }
.header-actions { display:flex; gap:10px; }
.terminal-layout { display:grid; grid-template-columns:300px minmax(0,1fr); gap:18px; align-items:start; }
.server-cell { display:grid; gap:6px; padding:2px 0; }
.server-name { font:600 14px inherit; }
.host-address, .muted { color:var(--muted); font-size:12px; }
.account-buttons { display:flex; gap:6px; flex-wrap:wrap; }
.terminal-workspace { min-height:560px; display:flex; flex-direction:column; }
.terminal-workspace :deep(.el-tabs) { display:flex; flex-direction:column; flex:1; min-height:0; }
.terminal-workspace :deep(.el-tabs__content) { flex:1; min-height:0; padding:0; }
.terminal-workspace :deep(.el-tab-pane) { height:calc(100vh - 250px); min-height:480px; }
@media (max-width:900px) { .terminal-layout { grid-template-columns:1fr; } }
</style>
