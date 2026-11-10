import { useContext, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/ui/Button'
import { Badge } from '../../components/ui/Badge'
import { createDeliveryApi, isValidTrackingNumber } from '../customer/api/delivery'
import type { PublicTrackingResponse } from '../customer/api/deliveryContracts'
import { createPricingApi } from '../customer/api/pricing'
import type { QuoteResponse, ZoneItem } from '../customer/api/pricingContracts'
import { formatCurrency, formatDateTime } from '../customer/components/customerUiUtils'
import { AuthContext } from '../../auth/context'
import { AssistantApiError, createAssistantApi, type AssistantApi, type AssistantTurn } from './assistantApi'

export interface AssistantMessage {
  id: string
  sender: 'user' | 'assistant'
  text: string
  timestamp: string
  trackingData?: PublicTrackingResponse
  quoteData?: QuoteResponse
  zonesData?: ZoneItem[]
  suggestedPrompts?: string[]
}

const INITIAL_PROMPTS = [
  'Track parcel SWD-20261026-A1B2C3',
  'Calculate 2.5kg quote from ZONE_1 to ZONE_2',
  'What delivery zones do you cover?',
  'How do I register as a Merchant?',
]

function getCurrentTimeString(): string {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function createUserMessage(query: string): AssistantMessage {
  return {
    id: `msg-${Date.now()}-user`,
    sender: 'user',
    text: query,
    timestamp: getCurrentTimeString(),
  }
}

function createErrorMessage(): AssistantMessage {
  return {
    id: `msg-${Date.now()}-err`,
    sender: 'assistant',
    text: 'I encountered an unexpected issue while querying the backend services. Please verify your connection or try again.',
    timestamp: getCurrentTimeString(),
    suggestedPrompts: ['What delivery zones do you cover?'],
  }
}

async function processQuery(query: string): Promise<AssistantMessage> {
  const deliveryClient = createDeliveryApi()
  const pricingClient = createPricingApi()
  const lower = query.toLowerCase()
  const now = getCurrentTimeString()

  // 1. Tool execution: Track Parcel
  // Look for SWD tracking pattern or explicit track commands
  const trackingMatch = query.match(/SWD-\d{8}-[A-Za-z0-9]+/i) || query.match(/TRK-[A-Za-z0-9-]+/i)
  if (trackingMatch || lower.startsWith('track')) {
    const trackingNumber = (trackingMatch ? trackingMatch[0] : query.replace(/^track\s+/i, '')).trim().toUpperCase()
    if (!isValidTrackingNumber(trackingNumber)) {
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: `"${trackingNumber}" does not appear to match the standard SwiftDrop format (e.g. SWD-YYYYMMDD-XXXX). Please check your reference code.`,
        timestamp: now,
        suggestedPrompts: ['Track parcel SWD-20261026-A1B2C3', 'Calculate 2.5kg quote from ZONE_1 to ZONE_2'],
      }
    }

    try {
      const trackingData = await deliveryClient.getTracking(trackingNumber)
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: `Here is the latest status for parcel **${trackingData.trackingNumber}**:`,
        timestamp: now,
        trackingData,
        suggestedPrompts: ['What delivery zones do you cover?', 'Calculate 2.5kg quote from ZONE_1 to ZONE_2'],
      }
    } catch {
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: `Parcel **${trackingNumber}** could not be found in Delivery Operations. Ensure the parcel has been booked and dispatched.`,
        timestamp: now,
        suggestedPrompts: ['Calculate 2.5kg quote from ZONE_1 to ZONE_2', 'What delivery zones do you cover?'],
      }
    }
  }

  // 2. Tool execution: Calculate Rate Quote
  if (lower.includes('quote') || lower.includes('rate') || lower.includes('price') || lower.includes('cost') || lower.includes('fee')) {
    const weightMatch = query.match(/(\d+(?:\.\d+)?)\s*(?:kg|kilos?)/i)
    const weight = weightMatch && weightMatch[1] ? parseFloat(weightMatch[1]) : 2.5

    let pickupZone = 'ZONE_1'
    let dropoffZone = 'ZONE_2'
    const zoneMatches = query.match(/ZONE_[1-5]/gi)
    if (zoneMatches && zoneMatches.length >= 2) {
      pickupZone = (zoneMatches[0] ?? 'ZONE_1').toUpperCase()
      dropoffZone = (zoneMatches[1] ?? 'ZONE_2').toUpperCase()
    } else if (zoneMatches && zoneMatches.length === 1) {
      dropoffZone = (zoneMatches[0] ?? 'ZONE_2').toUpperCase()
    }

    try {
      const quote = await pricingClient.calculateQuote({
        pickupZone,
        dropoffZone,
        weightKg: weight,
        lengthCm: 25,
        widthCm: 20,
        heightCm: 15,
      })

      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: `Here is your guaranteed rate quote for a **${weight} kg** parcel from **${pickupZone}** to **${dropoffZone}**:`,
        timestamp: now,
        quoteData: quote,
        suggestedPrompts: [
          'What delivery zones do you cover?',
          'Track parcel SWD-20261026-A1B2C3',
        ],
      }
    } catch {
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: 'Unable to calculate quote. Make sure zones are between ZONE_1 and ZONE_5 and weight is positive.',
        timestamp: now,
        suggestedPrompts: ['What delivery zones do you cover?'],
      }
    }
  }

  // 3. Tool execution: query delivery zones
  if (lower.includes('zone') || lower.includes('coverage') || lower.includes('province') || lower.includes('area')) {
    try {
      const zoneList = await pricingClient.listZones()
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: `SwiftDrop currently operates across **${zoneList.zones.length} delivery zones** under tariff **${zoneList.activeRateVersion}**:`,
        timestamp: now,
        zonesData: zoneList.zones,
        suggestedPrompts: [
          'Calculate 2.5kg quote from ZONE_1 to ZONE_2',
          'Track parcel SWD-20261026-A1B2C3',
        ],
      }
    } catch {
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: 'Unable to load delivery zones from Pricing service at this moment.',
        timestamp: now,
      }
    }
  }

  // 4. Guided Answers: Merchant Organization
  if (lower.includes('merchant') || lower.includes('business') || lower.includes('enterprise') || lower.includes('api key')) {
    return {
      id: `msg-${Date.now()}`,
      sender: 'assistant',
      text: 'Commercial merchants can enroll for self-service verification at **/merchant/enroll**. Once approved by platform administrators, you unlock bulk CSV order uploads, real-time webhook subscriptions, and scoped machine credentials (REST/gRPC).',
      timestamp: now,
      suggestedPrompts: [
        'Calculate 2.5kg quote from ZONE_1 to ZONE_2',
        'What delivery zones do you cover?',
      ],
    }
  }

  // 5. Guided Answers: Courier & Dispatch
  if (lower.includes('courier') || lower.includes('pod') || lower.includes('proof') || lower.includes('dispatch')) {
    return {
      id: `msg-${Date.now()}`,
      sender: 'assistant',
      text: 'Couriers receive assigned parcel rosters from dispatchers. At the customer doorstep, couriers capture recipient signatures and a photo as proof of delivery, which generates SHA-256 integrity hashes and official PDF delivery receipts.',
      timestamp: now,
      suggestedPrompts: [
        'Track parcel SWD-20261026-A1B2C3',
        'What delivery zones do you cover?',
      ],
    }
  }

  // General fallback
  return {
    id: `msg-${Date.now()}`,
    sender: 'assistant',
    text: "I can help with parcel tracking, tariff quotes, zone information, and workspace guidance. Try asking to track a specific tracking number or calculate a rate quote!",
    timestamp: now,
    suggestedPrompts: INITIAL_PROMPTS,
  }
}

type AssistantMode = 'checking' | 'ai' | 'keyword'

export function AiDeliveryAssistant({ assistantApi }: { assistantApi?: AssistantApi } = {}) {
  const auth = useContext(AuthContext)
  const getAccessToken = auth?.getAccessToken
  const api = useMemo(() => assistantApi ?? createAssistantApi({ getAccessToken }), [assistantApi, getAccessToken])
  const [mode, setMode] = useState<AssistantMode>('checking')
  const [isOpen, setIsOpen] = useState(false)
  const [input, setInput] = useState('')
  const [isProcessing, setIsProcessing] = useState(false)
  const [messages, setMessages] = useState<AssistantMessage[]>([
    {
      id: 'msg-welcome',
      sender: 'assistant',
      text: 'Hello! I am your SwiftDrop Logistics Assistant. I can track live parcels, give delivery quotes between our zones, or help you navigate our services.',
      timestamp: 'Today',
      suggestedPrompts: INITIAL_PROMPTS,
    },
  ])

  const messagesEndRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const drawerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (isOpen) {
      messagesEndRef.current?.scrollIntoView?.({ behavior: 'smooth' })
      inputRef.current?.focus()
    }
  }, [isOpen, messages])

  // Ask the server once (on first open) whether the AI provider is configured; otherwise use keyword mode.
  useEffect(() => {
    if (!isOpen || mode !== 'checking') return
    const controller = new AbortController()
    api.status(controller.signal)
      .then((result) => { if (!controller.signal.aborted) setMode(result.enabled ? 'ai' : 'keyword') })
      .catch(() => { if (!controller.signal.aborted) setMode('keyword') })
    return () => controller.abort()
  }, [api, isOpen, mode])

  // Accessible keyboard escape handler
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && isOpen) {
        setIsOpen(false)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen])

  async function handleSend(textToSend?: string) {
    const query = (textToSend || input).trim()
    if (!query || isProcessing) return

    const userMsg = createUserMessage(query)
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    setIsProcessing(true)

    try {
      if (mode === 'ai') {
        const turns: AssistantTurn[] = [...messages, userMsg]
          .filter((msg) => msg.id !== 'msg-welcome')
          .map((msg) => ({ role: msg.sender, text: msg.text }))
        try {
          const result = await api.chat(turns)
          setMessages((prev) => [...prev, {
            id: `msg-${Date.now()}-ai`, sender: 'assistant', text: result.reply, timestamp: getCurrentTimeString(),
          }])
          return
        } catch (error) {
          if (error instanceof AssistantApiError && error.kind === 'busy') {
            setMessages((prev) => [...prev, {
              id: `msg-${Date.now()}-busy`, sender: 'assistant', text: error.message, timestamp: getCurrentTimeString(),
            }])
            return
          }
          // Not configured or unreachable: answer this question with keyword mode instead of failing.
          if (error instanceof AssistantApiError && error.kind === 'not-configured') setMode('keyword')
          const fallback = await processQuery(query)
          setMessages((prev) => [...prev, { ...fallback, text: `(AI unavailable, using keyword mode) ${fallback.text}` }])
          return
        }
      }
      const response = await processQuery(query)
      setMessages((prev) => [...prev, response])
    } catch {
      setMessages((prev) => [...prev, createErrorMessage()])
    } finally {
      setIsProcessing(false)
    }
  }

  function handleFormSubmit(e: FormEvent) {
    e.preventDefault()
    void handleSend()
  }

  return (
    <>
      {/* Floating launcher trigger */}
      <button
        type="button"
        className="ai-assistant-launcher"
        onClick={() => setIsOpen((prev) => !prev)}
        aria-label={isOpen ? 'Close Help Assistant' : 'Open Help Assistant'}
        aria-expanded={isOpen}
      >
        <span className="ai-launcher-icon" aria-hidden="true">✨</span>
        <span className="ai-launcher-text">Help Assistant</span>
      </button>

      {/* Slide-over Drawer */}
      {isOpen && (
        <div
          ref={drawerRef}
          className="ai-assistant-drawer"
          role="dialog"
          aria-modal="false"
          aria-label="SwiftDrop Help Assistant"
        >
          {/* Header */}
          <div className="ai-drawer-header">
            <div className="ai-header-title-group">
              <span className="ai-status-indicator" aria-hidden="true" />
              <div>
                <h2 className="ai-drawer-title">{mode === 'ai' ? 'SwiftDrop AI Assistant' : 'SwiftDrop Help Assistant'}</h2>
                <p className="ai-drawer-subtitle">
                  {mode === 'ai'
                    ? 'AI answers from live SwiftDrop data · Powered by Gemini · Read-only'
                    : mode === 'checking'
                      ? 'Connecting…'
                      : 'Keyword commands: "track SWD-…", "quote", "help" · Not a generative AI'}
                </p>
              </div>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setIsOpen(false)}
              aria-label="Dismiss Help Assistant"
            >
              ✕
            </Button>
          </div>

          {/* Chat message body */}
          <div className="ai-drawer-body" role="log" aria-live="polite">
            {messages.map((msg) => (
              <div
                key={msg.id}
                className={`ai-message-row is-${msg.sender}`}
              >
                <div className="ai-message-bubble">
                  <div className="ai-message-text" style={{ whiteSpace: 'pre-wrap' }}>{msg.text}</div>

                  {/* Render Grounded Tracking Card */}
                  {msg.trackingData && (
                    <div className="ai-result-card tracking-card">
                      <div className="ai-card-header">
                        <Badge variant="brand">{msg.trackingData.currentStatus}</Badge>
                        <span className="text-xs font-mono text-muted">{msg.trackingData.trackingNumber}</span>
                      </div>
                      <div className="ai-route-summary">
                        {msg.trackingData.originCity && <div><strong>From:</strong> {msg.trackingData.originCity}</div>}
                        {msg.trackingData.destinationCity && <div><strong>To:</strong> {msg.trackingData.destinationCity}</div>}
                      </div>
                      {msg.trackingData.timeline.length > 0 && msg.trackingData.timeline[msg.trackingData.timeline.length - 1] && (
                        <div className="text-xs text-muted">
                          Last checkpoint: {formatDateTime(msg.trackingData.timeline[msg.trackingData.timeline.length - 1]!.occurredAt)}
                        </div>
                      )}
                      <Link
                        to={`/tracking/${encodeURIComponent(msg.trackingData.trackingNumber)}`}
                        className="button button-tonal button-sm ai-card-action"
                        onClick={() => setIsOpen(false)}
                      >
                        Open Live Checkpoints ↗
                      </Link>
                    </div>
                  )}

                  {/* Render Grounded Quote Card */}
                  {msg.quoteData && (
                    <div className="ai-result-card quote-card">
                      <div className="ai-card-header">
                        <Badge variant="accent">{msg.quoteData.sizeCategory}</Badge>
                        <span className="text-xs font-mono text-muted">{msg.quoteData.rateVersion}</span>
                      </div>
                      <div className="ai-quote-fee">
                        <span className="ai-fee-amount font-bold text-teal">
                          {formatCurrency(msg.quoteData.totalAmountMinorUnits, msg.quoteData.currency)}
                        </span>
                        <span className="text-xs text-muted">Guaranteed Total</span>
                      </div>
                      <div className="ai-quote-breakdown text-xs text-muted">
                        <div>Base Tariff: {formatCurrency(msg.quoteData.baseFeeMinorUnits, msg.quoteData.currency)}</div>
                        <div>Weight Surcharge: {formatCurrency(msg.quoteData.weightSurchargeMinorUnits, msg.quoteData.currency)}</div>
                        <div>Chargeable Weight: {msg.quoteData.chargeableWeightKg} kg</div>
                      </div>
                      <Link
                        to="/customer/quote"
                        className="button button-primary button-sm ai-card-action"
                        onClick={() => setIsOpen(false)}
                      >
                        Book This Delivery ↗
                      </Link>
                    </div>
                  )}

                  {/* Render Grounded Zone List Card */}
                  {msg.zonesData && (
                    <div className="ai-result-card zones-card">
                      <ul className="ai-zones-list">
                        {msg.zonesData.map((z) => (
                          <li key={z.zoneCode} className="ai-zone-item">
                            <span className="font-mono font-bold text-xs">{z.zoneCode}</span>
                            <span className="ai-zone-name">{z.name}</span>
                            <span className="ai-zone-transit text-xs text-muted">~{z.standardTransitHours}h</span>
                          </li>
                        ))}
                      </ul>
                      <Link
                        to="/customer/quote"
                        className="button button-tonal button-sm ai-card-action"
                        onClick={() => setIsOpen(false)}
                      >
                        Calculate Route Tariff ↗
                      </Link>
                    </div>
                  )}

                  <span className="ai-message-time">{msg.timestamp}</span>
                </div>

                {/* Suggested prompt chips */}
                {msg.suggestedPrompts && (
                  <div className="ai-prompts-tray">
                    {msg.suggestedPrompts.map((prompt) => (
                      <button
                        key={prompt}
                        type="button"
                        className="ai-prompt-chip"
                        onClick={() => void handleSend(prompt)}
                        disabled={isProcessing}
                      >
                        {prompt}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ))}

            {isProcessing && (
              <div className="ai-message-row is-assistant">
                <div className="ai-message-bubble ai-typing-bubble">
                  <span className="ai-typing-dot" />
                  <span className="ai-typing-dot" />
                  <span className="ai-typing-dot" />
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>

          {/* Footer input form */}
          <form className="ai-drawer-footer" onSubmit={handleFormSubmit}>
            <input
              ref={inputRef}
              type="text"
              className="ai-input"
              placeholder={mode === 'ai' ? 'Ask anything about your deliveries…' : 'Ask a question or enter tracking #...'}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              disabled={isProcessing}
              aria-label="Ask SwiftDrop Help Assistant"
            />
            <Button
              type="submit"
              variant="primary"
              size="sm"
              isLoading={isProcessing}
              disabled={!input.trim() || isProcessing}
            >
              Send
            </Button>
          </form>
        </div>
      )}
    </>
  )
}
