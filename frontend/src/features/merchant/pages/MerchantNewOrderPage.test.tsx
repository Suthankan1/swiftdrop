import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TestProviders, anonymousAuth } from '../../../test/authFixture'
import { MerchantNewOrderPage } from './MerchantNewOrderPage'
import { DeliveryError } from '../../customer/api/delivery'

const api = vi.hoisted(() => ({ listZones: vi.fn(), calculateQuote: vi.fn(), bookParcel: vi.fn() }))
vi.mock('../../customer/api/pricing', async importOriginal => ({
  ...await importOriginal<typeof import('../../customer/api/pricing')>(), createPricingApi: () => api,
}))
vi.mock('../../customer/api/delivery', async importOriginal => ({
  ...await importOriginal<typeof import('../../customer/api/delivery')>(), createDeliveryApi: () => api,
}))

beforeEach(() => {
  vi.clearAllMocks()
  api.listZones.mockResolvedValue({ zones: [{ zoneCode: 'A', name: 'Colombo' }, { zoneCode: 'B', name: 'Kandy' }] })
  api.calculateQuote.mockResolvedValue({ quoteId: 'quote-confirm', totalAmountMinorUnits: 125000, currency: 'LKR', expiresAt: '2099-01-01T00:00:00Z', isExpired: false })
  api.bookParcel.mockResolvedValue({ parcelId: 'parcel-confirm', trackingNumber: 'SWD-TEST-CONFIRM', amountMinorUnits: 125000, currency: 'LKR' })
})

async function fillOrder() {
  const user = userEvent.setup()
  render(<TestProviders auth={{ ...anonymousAuth, snapshot: { status: 'authenticated', subject: 'merchant-test', roles: ['MERCHANT'] } }}>
    <MemoryRouter><MerchantNewOrderPage /></MemoryRouter>
  </TestProviders>)
  await screen.findAllByRole('option', { name: 'Colombo' })
  await user.selectOptions(screen.getByLabelText('Pickup zone'), 'A')
  await user.selectOptions(screen.getByLabelText('Drop-off zone'), 'B')
  for (const [label, value] of [['Pickup address', 'Test pickup'], ['Drop-off address', 'Test destination'], ['Recipient name', 'Test Recipient'], ['Recipient phone', '+94771234567'], ['Your order reference', 'CONFIRM-1']]) {
    await user.type(screen.getByLabelText(label!), value!)
  }
  return user
}

describe('merchant quote confirmation', () => {
  it('prices without creating an order and cancellation keeps the form', async () => {
    const user = await fillOrder()
    await user.click(screen.getByRole('button', { name: 'Review price' }))
    expect(await screen.findByRole('heading', { name: 'Review your quote' })).toBeInTheDocument()
    expect(api.bookParcel).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Cancel quote and edit' }))
    expect(screen.getByLabelText('Your order reference')).toHaveValue('CONFIRM-1')
    expect(api.bookParcel).not.toHaveBeenCalled()
  })

  it.each([
    ['a lost response', new Error('network')],
    ['an in-progress conflict', new DeliveryError('conflict', 'Request is still being processed')],
  ])('books only on confirmation and keeps the quote/key after %s', async (_label, error) => {
    api.bookParcel.mockRejectedValueOnce(error)
    const user = await fillOrder()
    await user.click(screen.getByRole('button', { name: 'Review price' }))
    await user.click(await screen.findByRole('button', { name: 'Confirm and book order' }))
    await user.click(await screen.findByRole('button', { name: 'Retry confirmed order' }))
    expect(await screen.findByText('SWD-TEST-CONFIRM')).toBeInTheDocument()
    expect(api.calculateQuote).toHaveBeenCalledOnce()
    expect(api.bookParcel).toHaveBeenCalledTimes(2)
    expect(api.bookParcel.mock.calls[0]![1]).toBe(api.bookParcel.mock.calls[1]![1])
    expect(api.bookParcel.mock.calls[1]![0].quoteId).toBe('quote-confirm')
  })
})
