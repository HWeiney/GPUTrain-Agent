import type { JobLogEvent } from '@/api/train/jobs'

export function mergeJobLogs(existing: JobLogEvent[], incoming: JobLogEvent[]): JobLogEvent[] {
  const byId = new Map(existing.map(event => [event.eventId, event]))
  for (const event of incoming) byId.set(event.eventId, event)
  return [...byId.values()].sort((a, b) => a.eventId - b.eventId)
}

export class JobLogDecoder {
  private buffer = ''
  lastEventId: number

  constructor(lastEventId = 0) {
    this.lastEventId = lastEventId
  }

  push(chunk: string): JobLogEvent[] {
    this.buffer = (this.buffer + chunk).replace(/\r\n/g, '\n')
    const events: JobLogEvent[] = []
    let boundary = this.buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const frame = this.buffer.slice(0, boundary)
      this.buffer = this.buffer.slice(boundary + 2)
      const data = frame.split('\n').filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart()).join('\n')
      if (data) {
        try {
          const event = JSON.parse(data) as JobLogEvent
          if (Number.isSafeInteger(event.eventId) && event.eventId > this.lastEventId) {
            events.push(event)
            this.lastEventId = event.eventId
          }
        } catch {
          // An invalid frame is skipped; a later valid event can still advance the cursor.
        }
      }
      boundary = this.buffer.indexOf('\n\n')
    }
    return events
  }
}

export async function loadJobLogHistory(
  fetchPage: (afterEventId: number) => Promise<JobLogEvent[]>,
  startAfterEventId = 0
): Promise<JobLogEvent[]> {
  const events: JobLogEvent[] = []
  let cursor = startAfterEventId
  while (true) {
    const page = await fetchPage(cursor)
    for (const event of page) {
      if (event.eventId > cursor) {
        events.push(event)
        cursor = event.eventId
      }
    }
    if (page.length < 500 || !page.some(event => event.eventId > startAfterEventId)) break
    startAfterEventId = cursor
  }
  return events
}
