import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiKeyCreatedModal } from './ApiKeyCreatedModal'
import { WebhookSecretAlert } from './WebhookSecretAlert'
import { WebhookDeliveryLogsModal } from './WebhookDeliveryLogsModal'

const fixture = {
  keyId: 'fixture', name: 'Synthetic test key', keyPrefix: 'fixture', plaintextKey: 'synthetic-secret',
  scopes: ['parcels:read'], createdAt: '2026-11-14T00:00:00Z', expiresAt: '2026-11-15T00:00:00Z',
}
afterEach(() => vi.restoreAllMocks())

describe('merchant dialog feedback and keyboard behavior', () => {
  it('retries logs through the cancellable loader and aborts on close', async () => {
    const user = userEvent.setup()
    const fetchLogs = vi.fn<(id: string, signal?: AbortSignal) => Promise<{ totalElements: number; deliveries: [] }>>()
      .mockRejectedValueOnce(new Error('internal stack trace'))
      .mockImplementation(() => new Promise(() => {}))
    const view = render(<WebhookDeliveryLogsModal subscription={{
      subscriptionId: 'subscription-fixture', merchantId: 'merchant-fixture', httpsUrl: 'https://example.test/hooks',
      allowedEventTypes: ['parcel.status.changed.v1'], active: true, createdAt: '2026-11-14T00:00:00Z',
    }} onClose={vi.fn()} fetchDeliveries={fetchLogs} />)
    await user.click(await screen.findByRole('button', { name: 'Retry Loading' }))
    expect(fetchLogs).toHaveBeenCalledTimes(2)
    expect(screen.queryByText(/internal stack trace/)).not.toBeInTheDocument()
    const signal = fetchLogs.mock.calls[1]![1]!
    expect(signal.aborted).toBe(false)
    view.unmount()
    expect(signal.aborted).toBe(true)
  })
  it('does not claim a secret was copied when the clipboard rejects', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'))
    render(<ApiKeyCreatedModal createdKey={fixture} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Copy API key to clipboard' }))
    expect(await screen.findByText(/Copy failed/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'API key copied to clipboard' })).not.toBeInTheDocument()
  })
  it('reports webhook secret copy failure accurately', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'))
    render(<WebhookSecretAlert secret="synthetic-secret" endpointUrl="https://example.test/hooks" onDismiss={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Copy signing secret to clipboard' }))
    expect(await screen.findByText(/Copy failed/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Signing secret copied to clipboard' })).not.toBeInTheDocument()
  })
  it('contains Tab focus, closes on Escape, and restores the opener', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const close = vi.fn()
    const view = render(<ApiKeyCreatedModal createdKey={fixture} onClose={close} />)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveFocus()
    const buttons = screen.getAllByRole('button').filter(button => dialog.contains(button))
    buttons.at(-1)!.focus()
    fireEvent.keyDown(buttons.at(-1)!, { key: 'Tab' })
    expect(buttons[0]).toHaveFocus()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(close).toHaveBeenCalledOnce()
    view.unmount()
    expect(opener).toHaveFocus()
    opener.remove()
  })
})
