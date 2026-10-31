import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/ui/Button'
import { Badge } from '../../components/ui/Badge'
import { createDeliveryApi, isValidTrackingNumber } from '../customer/api/delivery'
import type { PublicTrackingResponse } from '../customer/api/deliveryContracts'
import { createPricingApi } from '../customer/api/pricing'
import type { QuoteResponse, ZoneItem } from '../customer/api/pricingContracts'
import { formatCurrency, formatDateTime } from '../customer/components/customerUiUtils'

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
  'What are the Western Province delivery zones?',
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
    suggestedPrompts: ['What are the Western Province delivery zones?'],
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
        text: `Here is the authoritative live status for shipment **${trackingData.trackingNumber}**:`,
        timestamp: now,
        trackingData,
        suggestedPrompts: ['What are the Western Province delivery zones?', 'Calculate 2.5kg quote from ZONE_1 to ZONE_2'],
      }
    } catch {
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: `Parcel **${trackingNumber}** could not be found in Delivery Operations. Ensure the parcel has been booked and dispatched.`,
        timestamp: now,
        suggestedPrompts: ['Calculate 2.5kg quote from ZONE_1 to ZONE_2', 'What are the Western Province delivery zones?'],
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
          'What are the Western Province delivery zones?',
          'Track parcel SWD-20261026-A1B2C3',
        ],
      }
    } catch {
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: 'Unable to calculate quote. Make sure zones are between ZONE_1 and ZONE_5 and weight is positive.',
        timestamp: now,
        suggestedPrompts: ['What are the Western Province delivery zones?'],
      }
    }
  }

  // 3. Tool execution: Query Western Province Zones
  if (lower.includes('zone') || lower.includes('coverage') || lower.includes('province') || lower.includes('area')) {
    try {
      const zoneList = await pricingClient.listZones()
      return {
        id: `msg-${Date.now()}`,
        sender: 'assistant',
        text: `SwiftDrop currently operates across **${zoneList.zones.length} delivery zones** in Western Province under tariff **${zoneList.activeRateVersion}**:`,
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
        'What are the Western Province delivery zones?',
      ],
    }
  }

  // 5. Guided Answers: Courier & Dispatch
  if (lower.includes('courier') || lower.includes('pod') || lower.includes('proof') || lower.includes('dispatch')) {
    return {
      id: `msg-${Date.now()}`,
      sender: 'assistant',
      text: 'Couriers receive assigned parcel rosters from dispatchers. At the customer doorstep, couriers capture recipient signatures and tamper-evident photo proof of delivery (POD), which generates SHA-256 integrity hashes and official PDF delivery receipts.',
      timestamp: now,
      suggestedPrompts: [
        'Track parcel SWD-20261026-A1B2C3',
        'What are the Western Province delivery zones?',
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

export function AiDeliveryAssistant() {
  const [isOpen, setIsOpen] = useState(false)
  const [input, setInput] = useState('')
  const [isProcessing, setIsProcessing] = useState(false)
  const [messages, setMessages] = useState<AssistantMessage[]>([
    {
      id: 'msg-welcome',
      sender: 'assistant',
      text: 'Hello! I am your SwiftDrop Logistics Assistant. I can track live parcels, calculate guaranteed rate quotes across Western Province zones, or help you navigate our services.',
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
        aria-label={isOpen ? 'Close AI Assistant' : 'Open AI Logistics Assistant'}
        aria-expanded={isOpen}
      >
        <span className="ai-launcher-icon" aria-hidden="true">✨</span>
        <span className="ai-launcher-text">AI Assistant</span>
      </button>

      {/* Slide-over Drawer */}
      {isOpen && (
        <div
          ref={drawerRef}
          className="ai-assistant-drawer"
          role="dialog"
          aria-modal="false"
          aria-label="SwiftDrop AI Logistics Assistant"
        >
          {/* Header */}
          <div className="ai-drawer-header">
            <div className="ai-header-title-group">
              <span className="ai-status-indicator" aria-hidden="true" />
              <div>
                <h2 className="ai-drawer-title">SwiftDrop AI Assistant</h2>
                <p className="ai-drawer-subtitle">Authoritative tools · Live cluster data</p>
              </div>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setIsOpen(false)}
              aria-label="Dismiss AI Assistant"
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
                  <div className="ai-message-text">{msg.text}</div>

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
                        to={`/tracking?trackingNumber=${encodeURIComponent(msg.trackingData.trackingNumber)}`}
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
              placeholder="Ask a question or enter tracking #..."
              value={input}
              onChange={(e) => setInput(e.target.value)}
              disabled={isProcessing}
              aria-label="Ask SwiftDrop AI Assistant"
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
