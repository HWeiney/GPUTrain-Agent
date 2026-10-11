import request from '@/utils/request'
import type { AjaxResult } from '@/types'
import type { ServerEntry } from './servers'

/** 终端握手票据：60 秒一次性，仅用于 WebSocket 握手。 */
export interface TerminalTicket {
  ticket: string
  expiresInMs: number
}

function apiRequest<T>(config: Record<string, any>): Promise<AjaxResult<T>> {
  return request(config) as Promise<AjaxResult<T>>
}

export function fetchTerminalTicket(serverId: number, accountId: number) {
  return apiRequest<TerminalTicket>({
    url: '/train/terminal/ticket', method: 'post', data: { serverId, accountId },
    headers: { repeatSubmit: false }
  })
}

export function listTerminalConnections() {
  return apiRequest<ServerEntry[]>({ url: '/train/terminal/connections', method: 'get' })
}
