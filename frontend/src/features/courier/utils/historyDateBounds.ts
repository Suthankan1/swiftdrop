const COLOMBO_UTC_OFFSET_MS = 5.5 * 60 * 60 * 1000

/** Half-open boundaries for the current calendar day in Asia/Colombo (UTC+05:30). */
export function colomboTodayBounds(now = new Date()): { since: Date; until: Date } {
  const colomboNow = new Date(now.getTime() + COLOMBO_UTC_OFFSET_MS)
  const since = new Date(Date.UTC(
    colomboNow.getUTCFullYear(),
    colomboNow.getUTCMonth(),
    colomboNow.getUTCDate(),
  ) - COLOMBO_UTC_OFFSET_MS)
  return { since, until: new Date(since.getTime() + 24 * 60 * 60 * 1000) }
}
