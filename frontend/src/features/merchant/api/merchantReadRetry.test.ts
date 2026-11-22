import { describe, expect, it } from 'vitest'
import { merchantReadRetry } from './merchantReadRetry'

describe('merchant safe read recovery', () => {
  it('bounds transient retries to two', () => {
    expect(merchantReadRetry(0, { kind: 'unavailable' })).toBe(true)
    expect(merchantReadRetry(1, { kind: 'timeout' })).toBe(true)
    expect(merchantReadRetry(2, { kind: 'unavailable' })).toBe(false)
  })
  it.each(['auth-required', 'forbidden', 'not-found', 'invalid-response', 'validation-error'])('never retries %s', kind => {
    expect(merchantReadRetry(0, { kind })).toBe(false)
  })
})
