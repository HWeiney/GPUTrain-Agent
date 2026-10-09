import request from '@/utils/request'
import type { AjaxResult } from '@/types'

function apiRequest<T>(config: Record<string, any>): Promise<AjaxResult<T>> {
  return request(config) as Promise<AjaxResult<T>>
}

export type ServerAuthType = 'PASSWORD' | 'PRIVATE_KEY'

export interface ServerCapabilities {
  ssh: boolean
  gpu: boolean
  systemdUser: boolean
  persistentUserManager: boolean
  appendLogs: boolean
}

export interface ServerView {
  serverId: number
  name: string
  host: string
  port: number
  hostKeyFingerprint: string
  enabled: boolean
  lastProbeAt: string | null
  lastSuccessAt: string | null
  status: string
  capabilities: ServerCapabilities
}

export interface ServerAccount {
  accountId: number
  serverId: number
  ownerUserId: number
  linuxUsername: string
  authType: ServerAuthType
  hasCredential: boolean
  allowedRoot: string
}

export interface ServerEntry {
  server: ServerView
  accounts: ServerAccount[]
}

export interface ServerSnapshot {
  sampledAt: string
  status: string
  errorCode: string | null
  capabilitiesJson: string
  factsJson: string
}

export interface ServerDetail {
  server: ServerView
  accounts: ServerAccount[]
  snapshot: ServerSnapshot | null
}

export interface ServerGpuDevice {
  serverId: number
  accountId: number
  gpuUuid: string
  name: string
  identityState: string
  schedulable: boolean
  sampledAt: string
  totalMemoryMiB: number
  usedMemoryMiB: number
  utilizationPercent: number
  migMode: string
  externalProcessCount: number
}

export interface ServerCreateRequest {
  name: string
  host: string
  port: number
  hostKeyFingerprint: string
  confirmDuplicateFingerprint: boolean
}

export interface ServerUpdateRequest {
  name?: string
  enabled?: boolean
  hostKeyFingerprint?: string
  confirmHostKeyChange?: boolean
  confirmDuplicateFingerprint?: boolean
}

export interface ServerAccountCreateRequest {
  ownerUserId: number
  linuxUsername: string
  authType: ServerAuthType
  credential: string
  passphrase: string
  allowedRoot: string
}

export interface ServerCredentialTestRequest {
  accountId?: number
  ownerUserId?: number
  linuxUsername?: string
  authType?: ServerAuthType
  credential: string
  passphrase: string
}

export interface ServerCredentialRotationRequest {
  rotate: true
  credential: string
  passphrase: string
}

export interface ServerConnectionTestResult {
  success: boolean
  errorCode: string | null
}

export function previewHostKey(host: string, port: number) {
  return apiRequest<{ algorithm: string; sha256: string }>({
    url: '/train/servers/host-key-preview', method: 'post', data: { host, port }
  })
}

export function createServer(data: ServerCreateRequest) {
  return apiRequest<ServerView>({ url: '/train/servers', method: 'post', data })
}

export function updateServer(serverId: number, data: ServerUpdateRequest) {
  return apiRequest<ServerView>({ url: `/train/servers/${serverId}`, method: 'put', data })
}

export function disableServer(serverId: number) {
  return apiRequest<void>({ url: `/train/servers/${serverId}`, method: 'delete' })
}

export function listServers() {
  return apiRequest<ServerEntry[]>({ url: '/train/servers', method: 'get' })
}

export function getServer(serverId: number, accountId?: number) {
  return apiRequest<ServerDetail>({
    url: `/train/servers/${serverId}`, method: 'get', params: accountId ? { accountId } : undefined
  })
}

export function assignServerAccount(serverId: number, data: ServerAccountCreateRequest) {
  return apiRequest<ServerAccount[]>({
    url: `/train/servers/${serverId}/accounts`, method: 'post', data,
    headers: { repeatSubmit: false }
  })
}

export function testServerConnection(serverId: number, data: ServerCredentialTestRequest) {
  return apiRequest<ServerConnectionTestResult>({
    url: `/train/servers/${serverId}/connection-test`, method: 'post', data,
    headers: { repeatSubmit: false }, timeout: 90_000
  })
}

export function rotateServerCredentials(serverId: number, accountId: number, data: ServerCredentialRotationRequest) {
  return apiRequest<ServerAccount>({
    url: `/train/servers/${serverId}/accounts/${accountId}`, method: 'put', data,
    headers: { repeatSubmit: false }
  })
}

export function probeServer(serverId: number, accountId: number) {
  return apiRequest<unknown>({
    url: `/train/servers/${serverId}/probe`, method: 'post', data: { accountId }, timeout: 90_000
  })
}

export function listServerDevices(serverId: number, accountId: number) {
  return apiRequest<ServerGpuDevice[]>({
    url: `/train/servers/${serverId}/devices`, method: 'get', params: { accountId }
  })
}
