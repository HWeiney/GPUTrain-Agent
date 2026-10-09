import type { ServerAccount, ServerAuthType, ServerView } from '@/api/train/servers'

interface ConnectionApi {
  previewHostKey(host: string, port: number): Promise<any>
  createServer(data: any): Promise<any>
  testServerConnection(serverId: number, data: any): Promise<any>
  assignServerAccount(serverId: number, data: any): Promise<any>
  rotateServerCredentials(serverId: number, accountId: number, data: any): Promise<any>
}

function responseErrorCode(error: any): string | undefined {
  return error?.response?.data?.errorCode ?? error?.response?.data?.data?.errorCode
}

export function useServerConnection(api: ConnectionApi) {
  const visible = ref(false)
  const busy = ref(false)
  const mode = ref<'new' | 'existing'>('new')
  const serverId = ref<number | null>(null)
  const addingAccount = ref(false)
  const accountId = ref<number | null>(null)
  const previewFingerprint = ref('')
  const previewAddress = ref('')
  const confirmedFingerprint = ref('')
  const testedSignature = ref('')
  const testSucceeded = ref(false)
  const errorCode = ref('')
  const duplicateFingerprintConfirmed = ref(false)
  const duplicateFingerprintTarget = ref('')
  let operationGeneration = 0
  const form = reactive({
    name: '', host: '', port: 22, ownerUserId: 0, linuxUsername: '',
    authType: 'PASSWORD' as ServerAuthType, credential: '', passphrase: '', allowedRoot: '', confirmed: false
  })

  const previewCurrent = computed(() => !!previewFingerprint.value && previewAddress.value === `${form.host}:${form.port}`)
  const canTest = computed(() => visible.value && !busy.value && previewCurrent.value
    && form.confirmed && confirmedFingerprint.value === previewFingerprint.value
    && !!form.linuxUsername.trim() && !!form.credential && !!form.allowedRoot.startsWith('/'))
  const signature = computed(() => [serverId.value, form.host, form.port, previewFingerprint.value,
    form.ownerUserId, form.linuxUsername, form.authType, form.allowedRoot,
    confirmedFingerprint.value, form.confirmed, form.credential, form.passphrase].join('\u0000'))
  const canSave = computed(() => visible.value && !busy.value && previewCurrent.value && form.confirmed
    && confirmedFingerprint.value === previewFingerprint.value && testSucceeded.value
    && testedSignature.value === signature.value && (mode.value === 'existing' || !!serverId.value))

  function resetSecrets() {
    form.credential = ''
    form.passphrase = ''
    testSucceeded.value = false
    testedSignature.value = ''
  }

  function openNew(ownerUserId: number) {
    operationGeneration++
    busy.value = false
    resetSecrets()
    mode.value = 'new'; serverId.value = null; addingAccount.value = false; accountId.value = null
    duplicateFingerprintConfirmed.value = false
    duplicateFingerprintTarget.value = ''
    previewFingerprint.value = ''; previewAddress.value = ''; confirmedFingerprint.value = ''
    errorCode.value = ''
    Object.assign(form, { name: '', host: '', port: 22, ownerUserId, linuxUsername: '', authType: 'PASSWORD', allowedRoot: '', confirmed: false })
    visible.value = true
  }

  function openExisting(server: Partial<ServerView>, account: Partial<ServerAccount>) {
    operationGeneration++
    busy.value = false
    resetSecrets()
    mode.value = 'existing'; serverId.value = server.serverId ?? null; addingAccount.value = false; accountId.value = account.accountId ?? null
    previewFingerprint.value = server.hostKeyFingerprint ?? ''
    previewAddress.value = `${server.host ?? ''}:${server.port ?? 22}`
    confirmedFingerprint.value = previewFingerprint.value
    errorCode.value = ''
    Object.assign(form, {
      name: server.name ?? '', host: server.host ?? '', port: server.port ?? 22,
      ownerUserId: account.ownerUserId ?? 0, linuxUsername: account.linuxUsername ?? '',
      authType: account.authType ?? 'PASSWORD', allowedRoot: account.allowedRoot ?? '', confirmed: true
    })
    visible.value = true
  }

  function openAccount(server: ServerView, ownerUserId: number) {
    operationGeneration++
    busy.value = false
    resetSecrets()
    mode.value = 'new'; serverId.value = server.serverId; addingAccount.value = true; accountId.value = null
    duplicateFingerprintConfirmed.value = false
    duplicateFingerprintTarget.value = ''
    previewFingerprint.value = server.hostKeyFingerprint
    previewAddress.value = `${server.host}:${server.port}`
    confirmedFingerprint.value = server.hostKeyFingerprint
    errorCode.value = ''
    Object.assign(form, {
      name: server.name, host: server.host, port: server.port, ownerUserId,
      linuxUsername: '', authType: 'PASSWORD', allowedRoot: '', confirmed: true
    })
    visible.value = true
  }

  async function preview() {
    const generation = operationGeneration
    const host = form.host.trim()
    const port = form.port
    const address = `${host}:${port}`
    errorCode.value = ''
    duplicateFingerprintConfirmed.value = false
    duplicateFingerprintTarget.value = ''
    if (!host || !Number.isInteger(port) || port < 1 || port > 65535) return
    busy.value = true
    try {
      const result = await api.previewHostKey(host, port)
      if (generation !== operationGeneration) return
      if (address !== `${form.host.trim()}:${form.port}`) return
      previewFingerprint.value = result.data.sha256
      previewAddress.value = address
      confirmedFingerprint.value = ''
      form.confirmed = false
      testSucceeded.value = false
      testedSignature.value = ''
    } catch (error: any) {
      if (generation !== operationGeneration) return
      previewFingerprint.value = ''; previewAddress.value = ''; confirmedFingerprint.value = ''
      errorCode.value = responseErrorCode(error) ?? 'PREVIEW_FAILED'
    } finally { if (generation === operationGeneration) busy.value = false }
  }

  function confirmFingerprint(confirmed: boolean) {
    form.confirmed = confirmed
    confirmedFingerprint.value = confirmed && previewCurrent.value ? previewFingerprint.value : ''
    testSucceeded.value = false
    testedSignature.value = ''
  }

  async function test() {
    if (!canTest.value) return false
    const generation = operationGeneration
    const initialInputSignature = [form.name, form.host, form.port, form.ownerUserId,
      form.linuxUsername, form.authType, form.allowedRoot, form.credential, form.passphrase,
      confirmedFingerprint.value, form.confirmed].join('\u0000')
    busy.value = true; errorCode.value = ''; testSucceeded.value = false
    try {
      if (mode.value === 'new' && !serverId.value) {
        const serverMetadata = {
          name: form.name.trim(), host: form.host.trim(), port: form.port,
          hostKeyFingerprint: confirmedFingerprint.value,
          confirmDuplicateFingerprint: duplicateFingerprintConfirmed.value && duplicateFingerprintTarget.value === `${form.host.trim()}:${form.port}:${confirmedFingerprint.value}`
        }
        const created = await api.createServer(serverMetadata)
        if (generation !== operationGeneration) return false
        serverId.value = created.data.serverId
        form.name = serverMetadata.name; form.host = serverMetadata.host; form.port = serverMetadata.port
        const currentInputSignature = [form.name, form.host, form.port, form.ownerUserId,
          form.linuxUsername, form.authType, form.allowedRoot, form.credential, form.passphrase,
          confirmedFingerprint.value, form.confirmed].join('\u0000')
        if (initialInputSignature !== currentInputSignature) {
          errorCode.value = 'CONNECTION_INPUT_CHANGED'
          return false
        }
      }
      if (generation !== operationGeneration) return false
      if (!serverId.value) throw new Error('SERVER_CREATE_FAILED')
      const payload: Record<string, unknown> = {
        credential: form.credential, passphrase: form.passphrase
      }
      if (mode.value === 'existing') payload.accountId = accountId.value
      else Object.assign(payload, { ownerUserId: form.ownerUserId, linuxUsername: form.linuxUsername.trim(), authType: form.authType })
      const attemptedSignature = signature.value
      const result = await api.testServerConnection(serverId.value, payload)
      if (generation !== operationGeneration) return false
      if (!result.data?.success) {
        errorCode.value = result.data?.errorCode ?? 'CONNECTION_TEST_FAILED'
        return false
      }
      if (attemptedSignature !== signature.value) {
        errorCode.value = 'CONNECTION_INPUT_CHANGED'
        return false
      }
      testSucceeded.value = true
      testedSignature.value = attemptedSignature
      return true
    } catch (error: any) {
      if (generation !== operationGeneration) return false
      errorCode.value = responseErrorCode(error) ?? error?.message ?? 'CONNECTION_TEST_FAILED'
      return false
    } finally { if (generation === operationGeneration) busy.value = false }
  }

  async function save() {
    if (!canSave.value || !serverId.value) return false
    const generation = operationGeneration
    busy.value = true
    try {
      if (mode.value === 'new') {
        await api.assignServerAccount(serverId.value, {
          ownerUserId: form.ownerUserId, linuxUsername: form.linuxUsername.trim(), authType: form.authType,
          credential: form.credential, passphrase: form.passphrase, allowedRoot: form.allowedRoot.trim()
        })
      } else if (accountId.value) {
        await api.rotateServerCredentials(serverId.value, accountId.value, {
          rotate: true, credential: form.credential, passphrase: form.passphrase
        })
      }
      if (generation !== operationGeneration) return false
      resetSecrets()
      errorCode.value = ''
      return true
    } catch (error: any) {
      if (generation !== operationGeneration) return false
      errorCode.value = responseErrorCode(error) ?? error?.message ?? 'SAVE_FAILED'
      resetSecrets()
      return false
    } finally { if (generation === operationGeneration) busy.value = false }
  }

  function confirmDuplicateServer() {
    duplicateFingerprintConfirmed.value = true
    duplicateFingerprintTarget.value = `${form.host.trim()}:${form.port}:${confirmedFingerprint.value}`
  }

  function close() {
    operationGeneration++
    busy.value = false
    duplicateFingerprintConfirmed.value = false
    duplicateFingerprintTarget.value = ''
    resetSecrets()
    visible.value = false
    previewFingerprint.value = ''; previewAddress.value = ''; confirmedFingerprint.value = ''
    form.confirmed = false
  }

  return { visible, busy, mode, serverId, addingAccount, accountId, form, previewFingerprint, previewCurrent,
    confirmedFingerprint, canTest, canSave, testSucceeded, errorCode, openNew, openExisting,
    openAccount, preview, confirmFingerprint, confirmDuplicateServer, test, save, close }
}
