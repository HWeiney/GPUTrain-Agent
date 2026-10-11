<template>
  <div class="app-container server-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">GPU TRAIN / CONNECTIONS</p>
        <h1>服务器连接</h1>
        <p class="intro">查看你有权访问的服务器、SSH 账户和最近一次成功采样。</p>
      </div>
      <div class="header-actions">
        <el-button :loading="loading" @click="load">刷新状态</el-button>
        <el-button v-if="permissions.manage" type="primary" @click="openNew">添加服务器</el-button>
      </div>
    </header>

    <el-alert v-if="loadError" title="服务器清单加载失败" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="entries" row-key="server.serverId" border class="server-table" empty-text="暂无可访问的服务器">
      <el-table-column label="服务器" min-width="190">
        <template #default="{ row }">
          <button class="server-name" type="button" @click="openDetail(row)">{{ row.server.name }}</button>
          <div class="host-address">{{ row.server.host }}:{{ row.server.port }}</div>
          <div v-if="!row.server.enabled" class="disabled-note">已停用</div>
        </template>
      </el-table-column>
      <el-table-column label="本人 Linux 账户" min-width="180">
        <template #default="{ row }">
          <div v-if="row.accounts.length" class="account-list">
            <div v-for="account in row.accounts" :key="account.accountId" class="account-row">
              <span>{{ account.linuxUsername }}</span>
              <el-tag size="small" effect="plain">{{ account.authType === 'PASSWORD' ? '密码' : 'SSH 私钥' }}</el-tag>
              <el-tag v-if="!account.hasCredential" size="small" type="warning">待配置</el-tag>
              <el-button v-if="account.hasCredential" link type="primary" @click="openRotation(row.server, account)">轮换凭据</el-button>
            </div>
          </div>
          <span v-else class="muted">尚未分配账户</span>
        </template>
      </el-table-column>
      <el-table-column label="连接状态" width="150">
        <template #default="{ row }"><el-tag :type="serverStatus(row.server, now).tone" effect="light">{{ serverStatus(row.server, now).label }}</el-tag></template>
      </el-table-column>
      <el-table-column label="最近成功采样" min-width="185">
        <template #default="{ row }">
          <span>{{ serverStatus(row.server).lastSuccess }}</span>
          <div v-if="!serverStatus(row.server, now).current && row.server.lastSuccessAt" class="stale-note">历史快照，仅供参考</div>
        </template>
      </el-table-column>
      <el-table-column label="能力" min-width="190">
        <template #default="{ row }">
          <div class="capabilities">
            <el-tag v-for="cap in capabilityLabels" :key="cap.key" size="small" :type="row.server.capabilities?.[cap.key] ? 'success' : 'info'" effect="plain">{{ cap.label }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="permissions.manage" label="管理" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="recoverHostKey(row.server)">更新主机指纹</el-button>
          <el-button v-if="row.server.enabled" link type="primary" @click="openAccount(row.server)">分配账户</el-button>
          <el-button v-if="row.server.enabled" link type="danger" @click="disable(row.server)">停用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="flow.visible.value" :title="flow.mode.value === 'new' ? '添加服务器账户' : '轮换 SSH 凭据'" width="min(680px, calc(100vw - 28px))" destroy-on-close :before-close="beforeClose">
      <div class="wizard">
        <section v-if="flow.mode.value === 'new'" class="wizard-section">
          <div class="section-heading"><span class="step-mark">A</span><div><h2>确认服务器地址</h2><p>先读取主机公钥指纹，确认它属于你要连接的机器。</p></div></div>
          <div class="form-grid">
            <el-form-item label="服务器名称"><el-input v-model="flow.form.name" :disabled="!!flow.serverId.value" maxlength="128" /></el-form-item>
            <el-form-item label="SSH 地址"><el-input v-model="flow.form.host" :disabled="!!flow.serverId.value" placeholder="gpu.example.com" /></el-form-item>
            <el-form-item label="端口"><el-input-number v-model="flow.form.port" :disabled="!!flow.serverId.value" :min="1" :max="65535" controls-position="right" /></el-form-item>
            <el-form-item label="所属用户 ID"><el-input-number v-model="flow.form.ownerUserId" :disabled="flow.busy.value" :min="1" controls-position="right" /></el-form-item>
          </div>
          <el-button :loading="flow.busy.value" :disabled="!!flow.serverId.value || !flow.form.host" @click="flow.preview">读取主机指纹</el-button>
          <div v-if="flow.previewFingerprint.value" class="fingerprint-panel">
            <span class="fingerprint-label">{{ flow.previewCurrent.value ? '当前地址的 SHA-256 指纹' : '地址已变化，请重新读取' }}</span>
            <code>{{ flow.previewFingerprint.value }}</code>
            <el-checkbox :model-value="flow.form.confirmed" :disabled="!flow.previewCurrent.value || flow.busy.value" @change="flow.confirmFingerprint(Boolean($event))">我已通过可信渠道核对这台服务器的指纹</el-checkbox>
          </div>
        </section>

        <section class="wizard-section">
          <div class="section-heading"><span class="step-mark">B</span><div><h2>{{ flow.mode.value === 'new' ? '配置 Linux 账户' : '输入新凭据' }}</h2><p>密码和私钥只用于连接测试与本次保存，不会写入浏览器存储。</p></div></div>
          <div v-if="flow.mode.value === 'new'" class="form-grid">
            <el-form-item label="Linux 用户名"><el-input v-model="flow.form.linuxUsername" :disabled="flow.busy.value" autocomplete="off" /></el-form-item>
            <el-form-item label="登录方式"><el-select v-model="flow.form.authType" :disabled="flow.busy.value"><el-option label="密码" value="PASSWORD" /><el-option label="SSH 私钥" value="PRIVATE_KEY" /></el-select></el-form-item>
          </div>
          <el-form-item :label="flow.form.authType === 'PASSWORD' ? 'SSH 密码' : 'SSH 私钥'">
            <el-input v-model="flow.form.credential" :type="flow.form.authType === 'PASSWORD' ? 'password' : 'textarea'" :rows="flow.form.authType === 'PASSWORD' ? 1 : 5" autocomplete="new-password" :show-password="flow.form.authType === 'PASSWORD'" />
          </el-form-item>
          <el-form-item v-if="flow.form.authType === 'PRIVATE_KEY'" label="私钥口令（可选）"><el-input v-model="flow.form.passphrase" type="password" autocomplete="new-password" show-password /></el-form-item>
          <el-form-item v-if="flow.mode.value === 'new'" label="允许的工作目录"><el-input v-model="flow.form.allowedRoot" placeholder="/home/username/training" autocomplete="off" /></el-form-item>
          <div class="test-row">
            <el-button type="primary" plain :loading="flow.busy.value" :disabled="!flow.canTest.value" @click="testConnection">测试 SSH 连接</el-button>
            <el-tag v-if="flow.testSucceeded.value" type="success">连接成功，可以保存凭据</el-tag>
            <el-tag v-if="flow.errorCode.value" type="danger">{{ errorText(flow.errorCode.value) }}</el-tag>
          </div>
          <p v-if="flow.mode.value === 'new' && flow.serverId.value" class="save-note">服务器地址已登记；账户凭据仍未保存。测试成功后点击“保存账户”。</p>
        </section>
      </div>
      <template #footer>
        <el-button @click="closeDialog">取消并清除输入</el-button>
        <el-button type="primary" :loading="flow.busy.value" :disabled="!flow.canSave.value" @click="saveCredentials">{{ flow.mode.value === 'new' ? '保存账户' : '保存新凭据' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="服务器状态详情" width="min(760px, calc(100vw - 28px))" @closed="invalidateDetail">
      <div v-if="detail" class="detail-view">
        <div class="detail-title"><div><h2>{{ detail.server.name }}</h2><code>{{ detail.server.host }}:{{ detail.server.port }}</code></div><el-tag :type="serverStatus(detail.server, now).tone">{{ serverStatus(detail.server, now).label }}</el-tag></div>
        <div class="fact-grid">
          <div><span>CPU Load (1m)</span><strong>{{ facts.load1 ?? '—' }}</strong></div>
          <div><span>可用内存</span><strong>{{ formatKiB(facts.memoryAvailableKiB) }}</strong></div>
          <div><span>总内存</span><strong>{{ formatKiB(facts.memoryTotalKiB) }}</strong></div>
          <div><span>可用磁盘</span><strong>{{ formatKiB(facts.diskAvailableKiB) }}</strong></div>
          <div><span>总磁盘</span><strong>{{ formatKiB(facts.diskTotalKiB) }}</strong></div>
          <div><span>采样时间</span><strong>{{ formatSampleTime(detail.snapshot?.sampledAt) }}</strong></div>
        </div>
        <div class="detail-toolbar">
          <el-select v-model="detailAccountId" aria-label="状态账户" @change="changeDetailAccount">
            <el-option v-for="account in detail.accounts" :key="account.accountId" :label="account.linuxUsername" :value="account.accountId" />
          </el-select>
          <el-button :loading="probing" :disabled="!detailAccountId" @click="runProbe">立即探测</el-button>
        </div>
        <el-table :data="detailDevices" border empty-text="当前账户没有 GPU 快照">
          <el-table-column prop="name" label="GPU" min-width="160" />
          <el-table-column prop="gpuUuid" label="UUID" min-width="230" />
          <el-table-column label="显存 (已用 / 总量)" min-width="170"><template #default="{ row }">{{ row.usedMemoryMiB }} / {{ row.totalMemoryMiB }} MiB</template></el-table-column>
          <el-table-column label="利用率" width="90"><template #default="{ row }">{{ row.utilizationPercent }}%</template></el-table-column>
          <el-table-column prop="externalProcessCount" label="外部进程" width="100" />
          <el-table-column label="状态" width="170"><template #default="{ row }"><el-tag :type="row.schedulable ? 'success' : 'warning'">{{ isSnapshotStale(row.sampledAt) ? '快照过期' : row.schedulable ? (row.identityState === 'EXTERNAL_OCCUPIED' ? '可用（显存充足）' : '可用') : row.identityState }}</el-tag></template></el-table-column>
        </el-table>
      </div>
      <el-empty v-else-if="!detailLoading" description="没有可显示的服务器状态" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { assignServerAccount, createServer, disableServer, getServer, listServerDevices, listServers, previewHostKey, probeServer, rotateServerCredentials, testServerConnection, updateServer, type ServerDetail, type ServerEntry, type ServerGpuDevice, type ServerView } from '@/api/train/servers'
import { formatSampleTime, isSnapshotStale, parseFacts, serverPermissions, serverStatus } from './serverStatus'
import { useServerConnection } from './useServerConnection'
import useUserStore from '@/store/modules/user'

const user = useUserStore()
const { proxy } = getCurrentInstance()!
const entries = ref<ServerEntry[]>([])
const loading = ref(false)
const loadError = ref(false)
const now = ref(Date.now())
const permissions = computed(() => serverPermissions(user.id, user.roles, user.permissions))
const capabilityLabels = [
  { key: 'ssh', label: 'SSH' }, { key: 'gpu', label: 'NVIDIA GPU' },
  { key: 'systemdUser', label: 'systemd --user' }, { key: 'persistentUserManager', label: 'Linger' }
] as const
const flow = useServerConnection({ previewHostKey, createServer, testServerConnection, assignServerAccount, rotateServerCredentials })
const detailVisible = ref(false)
const detailLoading = ref(false)
const probing = ref(false)
const detail = ref<ServerDetail | null>(null)
const detailDevices = ref<ServerGpuDevice[]>([])
const detailAccountId = ref<number | undefined>()
const facts = computed(() => parseFacts(detail.value?.snapshot?.factsJson))
let inventoryLoadGeneration = 0

async function load() {
  const generation = ++inventoryLoadGeneration
  loading.value = true; loadError.value = false
  try {
    const response = await listServers()
    if (generation === inventoryLoadGeneration) entries.value = response.data ?? []
  } catch { if (generation === inventoryLoadGeneration) { loadError.value = true; proxy?.$modal.msgError('服务器清单加载失败') } }
  finally { if (generation === inventoryLoadGeneration) loading.value = false }
}
function openNew() { flow.openNew(Number(user.id) || 0) }
function openAccount(server: ServerView) { flow.openAccount(server, Number(user.id) || 0) }
function openRotation(server: ServerView, account: ServerEntry['accounts'][number]) { flow.openExisting(server, account) }
function beforeClose(done: () => void) { flow.close(); done() }
function closeDialog() { flow.close() }
async function testConnection() {
  let passed = await flow.test()
  if (!passed && flow.errorCode.value === 'DUPLICATE_FINGERPRINT_CONFIRMATION_REQUIRED') {
    try {
      await ElMessageBox.confirm(`发现与其他登记地址相同的主机指纹：\n\n${flow.confirmedFingerprint.value}\n\n只有确认它们指向同一台物理服务器后才继续。`, '确认重复主机指纹', { type: 'warning', confirmButtonText: '确认同一服务器', cancelButtonText: '取消' })
      flow.confirmDuplicateServer()
      passed = await flow.test()
    } catch { return }
  }
  if (passed) ElMessage.success('SSH 连接测试成功；凭据尚未保存')
}
async function saveCredentials() {
  if (await flow.save()) {
    ElMessage.success(flow.mode.value === 'new' ? '服务器账户已保存' : 'SSH 凭据已更新')
    flow.close(); await load()
  }
}
async function disable(server: ServerView) {
  try {
    await ElMessageBox.confirm(`停用 ${server.name}？历史状态会保留，后续连接和探测会停止。`, '停用服务器', { type: 'warning', confirmButtonText: '确认停用' })
    await disableServer(server.serverId); ElMessage.success('服务器已停用'); await load()
  } catch { /* Cancelled or handled by request interceptor. */ }
}
async function recoverHostKey(server: ServerView) {
  let preview
  try {
    preview = await previewHostKey(server.host, server.port)
    await ElMessageBox.confirm(`请通过可信渠道核对新指纹：\n\n${preview.data?.sha256}\n\n确认这是 ${server.host}:${server.port} 的新主机密钥？`, '确认主机密钥变更', { type: 'warning', confirmButtonText: '确认并更新', cancelButtonText: '返回核对' })
    await updateServer(server.serverId, { hostKeyFingerprint: preview.data?.sha256, confirmHostKeyChange: true })
    ElMessage.success('主机指纹已更新'); await load()
  } catch (error: any) {
    const code = error?.response?.data?.errorCode ?? error?.response?.data?.data?.errorCode
    if (code === 'DUPLICATE_FINGERPRINT_CONFIRMATION_REQUIRED' && preview) {
      try {
        await ElMessageBox.confirm('这个指纹也登记在其他地址。确认两条地址确实指向同一台物理服务器？', '重复主机指纹', { type: 'warning', confirmButtonText: '确认同一服务器' })
        const refreshed = await previewHostKey(server.host, server.port)
        if (refreshed.data?.sha256 !== preview.data?.sha256) {
          await ElMessageBox.confirm(`主机指纹在核对期间发生变化。请再次通过可信渠道确认新指纹：\n\n${refreshed.data?.sha256}\n\n确认后才会更新。`, '再次确认主机密钥', { type: 'warning', confirmButtonText: '确认这个新指纹', cancelButtonText: '取消' })
        }
        await updateServer(server.serverId, { hostKeyFingerprint: refreshed.data?.sha256, confirmHostKeyChange: true, confirmDuplicateFingerprint: true })
        await load()
      } catch { /* Cancelled or handled by request interceptor. */ }
    } else if (code) ElMessage.error(errorText(code))
  }
}
let detailGeneration = 0
let detailOpenGeneration = 0
async function openDetail(entry: ServerEntry) {
  const openGeneration = ++detailOpenGeneration
  detailGeneration++
  detailVisible.value = true; detailLoading.value = true
  detailAccountId.value = entry.accounts[0]?.accountId; detailDevices.value = []; detail.value = null
  try { await refreshDetail() } catch { ElMessage.error('服务器详情加载失败') } finally { if (openGeneration === detailOpenGeneration) detailLoading.value = false }
}
function invalidateDetail() { detailOpenGeneration++; detailGeneration++ }
async function changeDetailAccount() {
  const openGeneration = ++detailOpenGeneration
  detail.value = null
  detailDevices.value = []
  detailLoading.value = true
  try { await refreshDetail() } catch { ElMessage.error('账户状态加载失败') }
  finally { if (openGeneration === detailOpenGeneration) detailLoading.value = false }
}
async function refreshDetail() {
  if (!detailAccountId.value) return
  const generation = ++detailGeneration
  const accountId = detailAccountId.value
  const serverId = detail.value?.server.serverId ?? entries.value.find((entry: ServerEntry) => entry.accounts.some((a) => a.accountId === accountId))?.server.serverId
  if (!serverId) return
  const [serverResponse, deviceResponse] = await Promise.all([
    getServer(serverId, accountId), listServerDevices(serverId, accountId)
  ])
  if (generation !== detailGeneration || accountId !== detailAccountId.value) return
  detail.value = serverResponse.data; detailDevices.value = deviceResponse.data ?? []
}
async function runProbe() {
  if (!detail.value || !detailAccountId.value) return
  probing.value = true
  try { await probeServer(detail.value.server.serverId, detailAccountId.value); await refreshDetail(); await load() }
  catch { ElMessage.error('服务器探测失败；请检查凭据和主机指纹') }
  finally { probing.value = false }
}
function formatKiB(value?: number) {
  if (value === undefined) return '—'
  const gib = value / 1024 / 1024
  return gib >= 1 ? `${gib.toFixed(1)} GiB` : `${(value / 1024).toFixed(0)} MiB`
}
function errorText(code: string) {
  const messages: Record<string, string> = {
    HOST_KEY_UNCONFIRMED: '请先核对并确认主机指纹', HOST_KEY_CHANGED: '主机指纹与已登记值不一致',
    AUTH_FAILED: '登录失败，请检查用户名和凭据', AUTH_TIMEOUT: 'SSH 登录超时',
    CONNECT_TIMEOUT: '连接超时', CONNECT_FAILED: '无法连接服务器', CREDENTIAL_INVALID: '凭据格式无效',
    ACCOUNT_INVALID: '账户或服务器不可用', DUPLICATE_SERVER_ADDRESS: '该地址已登记',
    DUPLICATE_FINGERPRINT_CONFIRMATION_REQUIRED: '发现重复主机指纹，需要再次确认',
    SERVER_CREATE_FAILED: '服务器信息登记失败', CONNECTION_TEST_FAILED: 'SSH 连接测试失败',
    CONNECTION_INPUT_CHANGED: '连接测试期间表单内容变化，请重新测试。'
  }
  return messages[code] ?? '操作失败，请检查输入后重试'
}
let statusTimer: number | undefined
onMounted(() => { load(); statusTimer = window.setInterval(() => { now.value = Date.now() }, 5_000) })
onBeforeUnmount(() => { if (statusTimer !== undefined) window.clearInterval(statusTimer); inventoryLoadGeneration++; invalidateDetail() })
</script>

<style scoped lang="scss">
.server-page { --ink:#172234; --muted:#647386; --line:#dbe3e9; --signal:#d58a1f; color:var(--ink); }
.page-header { display:flex; align-items:flex-end; justify-content:space-between; gap:22px; margin-bottom:22px; }
.eyebrow { margin:0; color:var(--signal); font:700 11px/1.3 "SFMono-Regular",Consolas,monospace; letter-spacing:.14em; }
h1 { margin:7px 0 6px; font-size:30px; letter-spacing:-.035em; }
.intro { margin:0; color:var(--muted); }
.header-actions { display:flex; gap:10px; }
.server-table { border-color:var(--line); }
.server-name { padding:0; border:0; color:#1f5367; background:none; font:600 15px inherit; text-align:left; cursor:pointer; }
.server-name:hover { text-decoration:underline; }
.host-address,.muted { color:var(--muted); font-size:12px; }
.disabled-note,.stale-note { margin-top:4px; color:#b36c28; font-size:12px; }
.account-list { display:grid; gap:7px; }
.account-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.capabilities { display:flex; gap:5px; flex-wrap:wrap; }
.wizard { display:grid; gap:22px; }
.wizard-section { padding-bottom:20px; border-bottom:1px solid var(--line); }
.wizard-section:last-child { padding-bottom:0; border:0; }
.section-heading { display:flex; align-items:flex-start; gap:12px; margin-bottom:15px; }
.step-mark { display:grid; width:27px; height:27px; flex:none; place-items:center; border-radius:3px; color:#fff; background:#273e51; font:700 12px "SFMono-Regular",Consolas,monospace; }
.section-heading h2 { margin:0 0 4px; font-size:16px; }
.section-heading p,.save-note { margin:0; color:var(--muted); font-size:13px; line-height:1.5; }
.form-grid { display:grid; grid-template-columns:1fr 1fr; gap:0 16px; }
.fingerprint-panel { display:grid; gap:8px; margin-top:14px; padding:14px; border:1px solid #d9e1e6; border-left:3px solid var(--signal); background:#f7f9fa; }
.fingerprint-label { color:var(--muted); font-size:12px; }
.fingerprint-panel code,.detail-title code { overflow-wrap:anywhere; color:#263c4e; font:12px/1.5 "SFMono-Regular",Consolas,monospace; }
.test-row { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.save-note { margin-top:10px; }
.detail-title,.detail-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:18px; }
.detail-title h2 { margin:0 0 4px; font-size:18px; }
.fact-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); border-top:1px solid var(--line); border-left:1px solid var(--line); margin-bottom:18px; }
.fact-grid div { display:grid; gap:7px; min-height:70px; padding:12px; border-right:1px solid var(--line); border-bottom:1px solid var(--line); }
.fact-grid span { color:var(--muted); font-size:12px; }
.fact-grid strong { font:600 15px "SFMono-Regular",Consolas,monospace; }
@media (max-width:760px) { .page-header { align-items:stretch; flex-direction:column; } .header-actions { justify-content:flex-start; } .form-grid { grid-template-columns:1fr; } .fact-grid { grid-template-columns:repeat(2,minmax(0,1fr)); } .account-row { gap:5px; } }
</style>
