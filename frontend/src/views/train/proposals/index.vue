<template>
  <div class="app-container proposals-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">GPU TRAIN / PROPOSALS</p>
        <h1>命令提案</h1>
        <p class="intro">从白名单动作目录创建提案，批准后经结构化 exec 通道在远端执行，结果与审计全程留痕。</p>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button @click="openAgentDraft">Agent 起草</el-button>
        <el-button type="primary" @click="openCreate">新建提案</el-button>
      </div>
    </header>

    <el-alert v-if="loadError" title="提案列表加载失败" type="error" :closable="false" show-icon />

    <el-table v-loading="loading" :data="proposals" row-key="proposalId" border empty-text="暂无命令提案">
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-collapse class="result-collapse">
            <el-collapse-item v-if="row.rationale" title="Agent 依据（rationale）" name="rationale">
              <pre class="result-pre">{{ row.rationale }}</pre>
            </el-collapse-item>
            <el-collapse-item title="参数（args）" name="args">
              <pre class="result-pre">{{ row.argsJson }}</pre>
            </el-collapse-item>
            <el-collapse-item v-if="row.stdout !== null" title="stdout" name="stdout">
              <pre class="result-pre">{{ row.stdout || '（无输出）' }}</pre>
            </el-collapse-item>
            <el-collapse-item v-if="row.stderr !== null" title="stderr" name="stderr">
              <pre class="result-pre">{{ row.stderr || '（无输出）' }}</pre>
            </el-collapse-item>
            <el-collapse-item v-if="row.errorCode" title="错误码" name="errorCode">
              <pre class="result-pre">{{ row.errorCode }}</pre>
            </el-collapse-item>
          </el-collapse>
        </template>
      </el-table-column>
      <el-table-column label="服务器 / 账户" min-width="170">
        <template #default="{ row }">
          <div class="target-cell">
            <span>{{ serverLabel(row.serverId) }}</span>
            <span class="muted">{{ accountLabel(row.accountId) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="action" label="动作" width="150" />
      <el-table-column label="渲染命令" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <code class="command-code">{{ row.renderedCommand }}</code>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90" align="center">
        <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAGS[row.status]">{{ STATUS_LABELS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="exitCode" width="90" align="center">
        <template #default="{ row }">
          <span v-if="row.exitCode !== null" :class="row.exitCode === 0 ? 'exit-ok' : 'exit-bad'">{{ row.exitCode }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center" fixed="right">
        <template #default="{ row }">
          <template v-if="canDecide(row.status)">
            <el-button size="small" type="primary" :loading="actingId === row.proposalId" @click="approve(row)">批准</el-button>
            <el-button size="small" :loading="actingId === row.proposalId" @click="reject(row)">拒绝</el-button>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建命令提案" width="560px" @closed="resetCreateForm">
      <el-form label-width="90px">
        <el-form-item label="服务器">
          <el-select v-model="createForm.serverId" placeholder="选择服务器" style="width: 100%" @change="onServerChange">
            <el-option v-for="entry in selectableEntries" :key="entry.server.serverId"
              :label="`${entry.server.name}（${entry.server.host}）`" :value="entry.server.serverId" />
          </el-select>
        </el-form-item>
        <el-form-item label="账户">
          <el-select v-model="createForm.accountId" placeholder="选择账户" :disabled="!createForm.serverId" style="width: 100%">
            <el-option v-for="account in selectableAccounts" :key="account.accountId"
              :label="account.linuxUsername" :value="account.accountId" />
          </el-select>
        </el-form-item>
        <el-form-item label="动作">
          <el-select v-model="createForm.action" placeholder="选择动作" style="width: 100%" @change="onActionChange">
            <el-option v-for="action in actions" :key="action.name" :label="action.name" :value="action.name" />
          </el-select>
        </el-form-item>
        <p v-if="selectedAction" class="action-desc">{{ selectedAction.description }}</p>
        <el-form-item v-for="param in selectedAction?.params ?? []" :key="param.name" :label="param.name" :required="param.required">
          <div class="param-field">
            <el-input-number v-if="paramInputType(param) === 'number'" v-model="numberParams[param.name]" :min="1" step-strictly class="param-input" />
            <el-input v-else v-model="textParams[param.name]" :placeholder="param.description" class="param-input" />
            <span class="param-hint">{{ param.description }}</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!createReady" @click="submitCreate">创建提案</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="agentVisible" title="Agent 起草提案" width="560px" @closed="resetAgentForm">
      <el-form label-width="90px">
        <el-form-item label="服务器">
          <el-select v-model="agentForm.serverId" placeholder="选择服务器" style="width: 100%" @change="onAgentServerChange">
            <el-option v-for="entry in selectableEntries" :key="entry.server.serverId"
              :label="`${entry.server.name}（${entry.server.host}）`" :value="entry.server.serverId" />
          </el-select>
        </el-form-item>
        <el-form-item label="账户">
          <el-select v-model="agentForm.accountId" placeholder="选择账户" :disabled="!agentForm.serverId" style="width: 100%">
            <el-option v-for="account in agentAccounts" :key="account.accountId"
              :label="account.linuxUsername" :value="account.accountId" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="agentForm.serverId !== null" label="Agent 记忆">
          <div class="memory-panel">
            <p v-if="agentMemoryLoading" class="memory-hint muted">记忆加载中…</p>
            <p v-else-if="agentMemoryError" class="memory-hint muted">记忆加载失败，不影响起草</p>
            <p v-else-if="agentMemory.length === 0" class="memory-hint muted">这台服务器还没有历史交互，Agent 将从零开始理解你的意图</p>
            <ul v-else class="memory-list">
              <li v-for="turn in agentMemory" :key="turn.memoryId" class="memory-turn">
                <div class="memory-head">
                  <span class="memory-time">{{ formatTime(turn.createdAt) }}</span>
                  <el-tag v-if="turn.status !== null" :type="STATUS_TAGS[turn.status]" size="small">{{ STATUS_LABELS[turn.status] }}</el-tag>
                </div>
                <p class="memory-line">{{ memoryTurnSummary(turn) }}</p>
              </li>
            </ul>
          </div>
        </el-form-item>
        <el-form-item label="意图">
          <el-input v-model="agentForm.intentText" type="textarea" :rows="4" maxlength="4000" show-word-limit
            placeholder="用一句话描述你想在这台服务器上做什么，Agent 会从白名单动作目录中选择动作并起草提案，批准前不会执行任何命令" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="agentVisible = false">取消</el-button>
        <el-button type="primary" :loading="agentCreating" :disabled="!agentReady" @click="submitAgentDraft">生成提案</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {
  approveProposal, createProposal, draftProposal, listAgentMemory, listProposalActions, listProposals, rejectProposal,
  type AgentMemoryTurn, type CommandProposal, type ProposalActionView, type ProposalSource
} from '@/api/train/proposals'
import { listServers } from '@/api/train/servers'
import type { ServerAccount, ServerEntry } from '@/api/train/servers'
import { agentDraftReady, canDecide, collectArgs, memoryTurnSummary, paramInputType, proposalDraftReady, STATUS_LABELS, STATUS_TAGS } from './proposalForm'

const { proxy } = getCurrentInstance()!
const proposals = ref<CommandProposal[]>([])
const actions = ref<ProposalActionView[]>([])
const entries = ref<ServerEntry[]>([])
const loading = ref(false)
const loadError = ref(false)
const actingId = ref('')
const creating = ref(false)
const createVisible = ref(false)
const agentVisible = ref(false)
const agentCreating = ref(false)
/** Agent 记忆按 (用户, 服务器) 隔离：切换服务器重新拉取，加载失败不阻塞起草。 */
const agentMemory = ref<AgentMemoryTurn[]>([])
const agentMemoryLoading = ref(false)
const agentMemoryError = ref(false)
let agentMemoryGeneration = 0

const createForm = reactive({ serverId: null as number | null, accountId: null as number | null, action: '' })
/** Agent 起草表单：独立于手动创建表单，避免级联状态互串。 */
const agentForm = reactive({ serverId: null as number | null, accountId: null as number | null, intentText: '' })
/** 动态表单参数按类型分桶：long 用数字输入，其余按文本输入。 */
const numberParams = reactive<Record<string, number | undefined>>({})
const textParams = reactive<Record<string, string>>({})

let loadGeneration = 0
async function load() {
  const generation = ++loadGeneration
  loading.value = true; loadError.value = false
  try {
    const [proposalResponse, actionResponse, serverResponse] = await Promise.all([
      listProposals(), listProposalActions(), listServers()
    ])
    if (generation === loadGeneration) {
      proposals.value = proposalResponse.data ?? []
      actions.value = actionResponse.data ?? []
      entries.value = serverResponse.data ?? []
    }
  } catch {
    if (generation === loadGeneration) { loadError.value = true; proxy?.$modal.msgError('提案列表加载失败') }
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

const serverNames = computed(() => {
  const map = new Map<number, string>()
  for (const entry of entries.value) map.set(entry.server.serverId, entry.server.name)
  return map
})
const accountNames = computed(() => {
  const map = new Map<number, string>()
  for (const entry of entries.value) for (const account of entry.accounts) map.set(account.accountId, account.linuxUsername)
  return map
})
function serverLabel(serverId: number): string {
  return serverNames.value.get(serverId) ?? `服务器 #${serverId}`
}
function accountLabel(accountId: number): string {
  return accountNames.value.get(accountId) ?? `账户 #${accountId}`
}
function sourceLabel(source: ProposalSource): string {
  return source === 'AGENT' ? 'Agent' : '手动'
}
function formatTime(value: string | null): string {
  if (!value || !Number.isFinite(new Date(value).getTime())) return '暂无'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date(value))
}

async function approve(row: CommandProposal) {
  actingId.value = row.proposalId
  try {
    await approveProposal(row.proposalId)
    proxy?.$modal.msgSuccess('提案已执行')
    await load()
  } catch {
    proxy?.$modal.msgError('提案执行失败')
  } finally {
    actingId.value = ''
  }
}

async function reject(row: CommandProposal) {
  actingId.value = row.proposalId
  try {
    await rejectProposal(row.proposalId)
    proxy?.$modal.msgSuccess('提案已拒绝')
    await load()
  } catch {
    proxy?.$modal.msgError('提案拒绝失败')
  } finally {
    actingId.value = ''
  }
}

const selectableEntries = computed(() =>
  entries.value.filter((entry: ServerEntry) => entry.server.enabled && entry.accounts.some((account: ServerAccount) => account.hasCredential)))
const selectableAccounts = computed(() =>
  entries.value.find((entry: ServerEntry) => entry.server.serverId === createForm.serverId)?.accounts
    .filter((account: ServerAccount) => account.hasCredential) ?? [])
const agentAccounts = computed(() =>
  entries.value.find((entry: ServerEntry) => entry.server.serverId === agentForm.serverId)?.accounts
    .filter((account: ServerAccount) => account.hasCredential) ?? [])
const selectedAction = computed(() => actions.value.find((action: ProposalActionView) => action.name === createForm.action) ?? null)

function openCreate() {
  if (!selectableEntries.value.length) {
    proxy?.$modal.msgError('暂无可用服务器账户，请先在服务器管理中配置')
    return
  }
  createVisible.value = true
}

function onServerChange() {
  createForm.accountId = null
}

function onActionChange() {
  for (const key of Object.keys(numberParams)) delete numberParams[key]
  for (const key of Object.keys(textParams)) delete textParams[key]
}

function openAgentDraft() {
  if (!selectableEntries.value.length) {
    proxy?.$modal.msgError('暂无可用服务器账户，请先在服务器管理中配置')
    return
  }
  agentVisible.value = true
}

function onAgentServerChange() {
  agentForm.accountId = null
  loadAgentMemory()
}

/** 拉取所选服务器的最近交互记忆；generation 防止快速切换服务器时旧响应覆盖新选择。 */
async function loadAgentMemory() {
  const serverId = agentForm.serverId
  const generation = ++agentMemoryGeneration
  if (serverId === null) {
    agentMemory.value = []
    agentMemoryError.value = false
    return
  }
  agentMemoryLoading.value = true
  agentMemoryError.value = false
  try {
    const response = await listAgentMemory(serverId)
    if (generation === agentMemoryGeneration) agentMemory.value = response.data ?? []
  } catch {
    if (generation === agentMemoryGeneration) agentMemoryError.value = true
  } finally {
    if (generation === agentMemoryGeneration) agentMemoryLoading.value = false
  }
}

const agentReady = computed(() => agentDraftReady(agentForm.serverId, agentForm.accountId, agentForm.intentText))

async function submitAgentDraft() {
  if (agentForm.serverId === null || agentForm.accountId === null) return
  agentCreating.value = true
  try {
    await draftProposal({
      serverId: agentForm.serverId,
      accountId: agentForm.accountId,
      intentText: agentForm.intentText.trim()
    })
    proxy?.$modal.msgSuccess('Agent 提案已创建，等待批准')
    agentVisible.value = false
    await load()
  } catch {
    proxy?.$modal.msgError('Agent 提案起草失败')
  } finally {
    agentCreating.value = false
  }
}

function resetAgentForm() {
  agentForm.serverId = null
  agentForm.accountId = null
  agentForm.intentText = ''
  agentMemoryGeneration++
  agentMemory.value = []
  agentMemoryError.value = false
  agentMemoryLoading.value = false
}

const createReady = computed(() => proposalDraftReady(selectedAction.value, createForm, numberParams, textParams))

async function submitCreate() {
  const action = selectedAction.value
  if (createForm.serverId === null || createForm.accountId === null || !action) return
  const args = collectArgs(action, numberParams, textParams)
  creating.value = true
  try {
    await createProposal({ serverId: createForm.serverId, accountId: createForm.accountId, action: action.name, args })
    proxy?.$modal.msgSuccess('提案已创建，等待批准')
    createVisible.value = false
    await load()
  } catch {
    proxy?.$modal.msgError('提案创建失败')
  } finally {
    creating.value = false
  }
}

function resetCreateForm() {
  createForm.serverId = null
  createForm.accountId = null
  createForm.action = ''
  onActionChange()
}

onMounted(load)
onBeforeUnmount(() => { loadGeneration++ })
</script>

<style scoped lang="scss">
.proposals-page { --ink:#172234; --muted:#647386; --line:#dbe3e9; --signal:#d58a1f; color:var(--ink); }
.page-header { display:flex; align-items:flex-end; justify-content:space-between; gap:22px; margin-bottom:22px; }
.eyebrow { margin:0; color:var(--signal); font:700 11px/1.3 "SFMono-Regular",Consolas,monospace; letter-spacing:.14em; }
h1 { margin:7px 0 6px; font-size:30px; letter-spacing:-.035em; }
.intro { margin:0; color:var(--muted); }
.header-actions { display:flex; gap:10px; }
.target-cell { display:grid; gap:3px; padding:2px 0; }
.muted { color:var(--muted); font-size:12px; }
.command-code { font:12px/1.5 "SFMono-Regular",Consolas,monospace; color:var(--ink); }
.exit-ok { color:#2f9e6e; font-weight:600; }
.exit-bad { color:#d64545; font-weight:600; }
.result-collapse { border-top:1px solid var(--line); border-bottom:none; padding:0 12px; }
.result-pre { margin:0; max-height:280px; overflow:auto; font:12px/1.6 "SFMono-Regular",Consolas,monospace; color:var(--ink); white-space:pre-wrap; word-break:break-all; background:#f7f9fb; border:1px solid var(--line); border-radius:6px; padding:10px 12px; }
.action-desc { margin:2px 0 14px 90px; color:var(--muted); font-size:12px; }
.param-field { display:grid; gap:4px; width:100%; }
.param-input { width:100%; }
.param-hint { color:var(--muted); font-size:12px; }
.memory-panel { width:100%; }
.memory-hint { margin:0; }
.memory-list { margin:0; padding:0; list-style:none; display:grid; gap:8px; max-height:216px; overflow:auto; }
.memory-turn { display:grid; gap:5px; padding:8px 12px; border:1px solid var(--line); border-radius:6px; background:#f7f9fb; }
.memory-head { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.memory-time { color:var(--muted); font-size:12px; }
.memory-line { margin:0; font-size:12.5px; line-height:1.6; color:var(--ink); white-space:pre-wrap; word-break:break-all; }
</style>
