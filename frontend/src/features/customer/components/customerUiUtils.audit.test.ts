import { describe, expect, it } from 'vitest'
import { formatDimensions, formatWeight, getFailedStepIndex, LIFECYCLE_STEPS } from './customerUiUtils'

describe('honest parcel display helpers', () => {
  it('marks only the step that did not happen for a failed parcel', () => {
    // Failed at pickup: CREATED reached, PICKED_UP is the failed step.
    expect(getFailedStepIndex('CREATED')).toBe(LIFECYCLE_STEPS.indexOf('PICKED_UP'))
    expect(getFailedStepIndex('OUT_FOR_DELIVERY')).toBe(LIFECYCLE_STEPS.indexOf('DELIVERED'))
    // Unknown history: nothing is shown as completed.
    expect(getFailedStepIndex(undefined)).toBe(-1)
    expect(getFailedStepIndex('NOT_A_STATUS')).toBe(-1)
  })

  it('never invents weights or dimensions', () => {
    expect(formatWeight(2.5)).toBe('2.5 kg')
    expect(formatWeight(0)).toBe('Not recorded')
    expect(formatWeight(Number.NaN)).toBe('Not recorded')
    expect(formatDimensions('20x15x10')).toBe('20x15x10 cm')
    expect(formatDimensions('STANDARD')).toBe('Not recorded')
    expect(formatDimensions(null)).toBe('Not recorded')
  })
})
