import { describe, expect, it } from 'vitest'
import { JobLogDecoder, loadJobLogHistory, mergeJobLogs } from './jobLogStream'
import type { JobLogEvent } from '@/api/train/jobs'

describe('JobLogDecoder', () => {
  it('reassembles split SSE frames and ignores duplicate IDs', () => {
    const decoder = new JobLogDecoder(17)
    expect(decoder.push('id: 18\r\ndata: {"eventId":18,"text":"line')).toEqual([])
    const events = decoder.push(' one"}\r\n\r\nid: 18\ndata: {"eventId":18,"text":"duplicate"}\n\n')
    expect(events.map(event => event.text)).toEqual(['line one'])
    expect(decoder.lastEventId).toBe(18)
  })

  it('starts from history cursor after reconnect', () => {
    const decoder = new JobLogDecoder(18)
    expect(decoder.push('data: {"eventId":17,"text":"old"}\n\ndata: {"eventId":19,"text":"new"}\n\n').map(event => event.eventId)).toEqual([19])
  })

  it('reassembles CRLF when the carriage return and newline arrive separately', () => {
    const decoder = new JobLogDecoder()
    expect(decoder.push('id: 1\r')).toEqual([])
    expect(decoder.push('\ndata: {"eventId":1,"text":"ready"}\r')).toEqual([])
    expect(decoder.push('\n\r')).toEqual([])
    expect(decoder.push('\n').map(event => event.text)).toEqual(['ready'])
  })
})

describe('loadJobLogHistory', () => {
  it('continues after a full page without repeating events', async () => {
    const cursors: number[] = []
    const events = await loadJobLogHistory(async cursor => {
      cursors.push(cursor)
      const from = cursor + 1
      const count = cursor === 0 ? 500 : 1
      return Array.from({ length: count }, (_, i): JobLogEvent => ({
        eventId: from + i, text: 'line', stream: 'STDOUT', offset: i,
        nextOffset: i + 1, createdAt: '2026-10-07T12:00:00Z'
      }))
    })
    expect(cursors).toEqual([0, 500])
    expect(events.length).toBe(501)
  })
})

describe('mergeJobLogs', () => {
  it('keeps SSE and delayed history events ordered without duplicates', () => {
    const event = (eventId: number) => ({ eventId, text: `${eventId}` }) as JobLogEvent
    expect(mergeJobLogs([event(1), event(4)], [event(2), event(4), event(3)]).map(item => item.eventId))
      .toEqual([1, 2, 3, 4])
  })
})
