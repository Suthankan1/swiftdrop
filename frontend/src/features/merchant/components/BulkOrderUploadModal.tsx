import { useContext, useMemo, useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'
import { AuthContext } from '../../../auth/context'
import { createDeliveryApi, DeliveryError } from '../../customer/api/delivery'
import { createPricingApi } from '../../customer/api/pricing'
import {
  bookMerchantOrder,
  MERCHANT_ORDER_CSV_SAMPLE,
  newBookingAttempt,
  parseMerchantOrdersCsv,
  type BookingAttempt,
  type ParsedOrderRow,
} from '../api/merchantBooking'

export interface BulkOrderUploadModalProps {
  isOpen: boolean
  onClose: () => void
  onImportComplete?: (count: number) => void
  /** Test seam: replaces the Pricing and Delivery clients. */
  bookingApis?: Parameters<typeof bookMerchantOrder>[0]
}

type RowOutcome = { state: 'booked'; trackingNumber: string } | { state: 'failed' | 'unknown'; message: string }

export function BulkOrderUploadModal({ isOpen, onClose, onImportComplete, bookingApis }: BulkOrderUploadModalProps) {
  const auth = useContext(AuthContext)
  const getAccessToken = auth?.getAccessToken
  const apis = useMemo(() => bookingApis ?? {
    pricing: createPricingApi({ getAccessToken: getAccessToken ?? (async () => null) }),
    delivery: createDeliveryApi({ getAccessToken: getAccessToken ?? (async () => null) }),
  }, [bookingApis, getAccessToken])
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [rows, setRows] = useState<ParsedOrderRow[]>([])
  const [outcomes, setOutcomes] = useState<Record<number, RowOutcome>>({})
  const [importing, setImporting] = useState(false)
  const [progress, setProgress] = useState<string | null>(null)
  const [isDragOver, setIsDragOver] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const attempts = useRef(new Map<number, BookingAttempt>())
  const cancelled = useRef(false)

  if (!isOpen) return null

  const validRows = rows.filter((r) => !r.error)
  const hasErrors = rows.some((r) => r.error)
  const pendingRows = validRows.filter((r) => outcomes[r.rowNumber]?.state !== 'booked')

  function reset() {
    setFile(null)
    setRows([])
    setOutcomes({})
    setProgress(null)
    attempts.current = new Map()
  }

  function handleFileSelected(selectedFile: File) {
    if (!selectedFile.name.toLowerCase().endsWith('.csv')) {
      setFileError('Please upload a valid .csv file.')
      return
    }
    if (selectedFile.size > 1_000_000) {
      setFileError('The file is larger than 1 MB. Split it into smaller imports.')
      return
    }
    setFileError(null)
    reset()
    setFile(selectedFile)
    const reader = new FileReader()
    reader.onload = (e) => {
      const result = parseMerchantOrdersCsv(String(e.target?.result ?? ''))
      if (result.error) setFileError(result.error)
      setRows(result.rows)
    }
    reader.readAsText(selectedFile)
  }

  /** Books the remaining valid rows one at a time. Failed rows can be retried without duplicates. */
  async function handleImport() {
    if (importing || pendingRows.length === 0) return
    cancelled.current = false
    setImporting(true)
    let booked = 0
    for (const [position, row] of pendingRows.entries()) {
      if (cancelled.current) break
      setProgress(`Booking order ${position + 1} of ${pendingRows.length}…`)
      const attempt = attempts.current.get(row.rowNumber) ?? newBookingAttempt()
      attempts.current.set(row.rowNumber, attempt)
      try {
        const parcel = await bookMerchantOrder(apis, row.order, attempt)
        booked++
        setOutcomes((current) => ({ ...current, [row.rowNumber]: { state: 'booked', trackingNumber: parcel.trackingNumber } }))
      } catch (error) {
        const definite = error instanceof DeliveryError && ['validation-error', 'forbidden', 'expired', 'conflict'].includes(error.kind)
        if (definite) attempts.current.delete(row.rowNumber) // a fresh quote and key are safe after a definite rejection
        const message = error instanceof Error && error.message ? error.message : 'Booking failed.'
        setOutcomes((current) => ({
          ...current,
          [row.rowNumber]: { state: definite ? 'failed' : 'unknown', message: definite ? message : `${message} Retry is safe: it will not book twice.` },
        }))
        if (error instanceof DeliveryError && error.kind === 'auth-required') break
      }
    }
    setImporting(false)
    setProgress(null)
    if (booked > 0) onImportComplete?.(booked)
  }

  function handleDownloadSample() {
    const blob = new Blob([MERCHANT_ORDER_CSV_SAMPLE], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'swiftdrop-bulk-orders-sample.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  const bookedCount = Object.values(outcomes).filter((o) => o.state === 'booked').length

  return (
    <div className="modal-backdrop" role="presentation" onClick={() => { if (!importing) onClose() }}>
      <div
        className="modal-panel"
        style={{ maxWidth: '680px', width: '90%' }}
        role="dialog"
        aria-modal="true"
        aria-labelledby="bulk-upload-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div>
            <Badge variant="primary">Bulk Consignment</Badge>
            <h2 id="bulk-upload-title" className="modal-title" style={{ marginTop: '4px' }}>
              Import Orders via CSV
            </h2>
          </div>
          <button
            type="button"
            className="modal-close-btn"
            aria-label="Close bulk upload modal"
            onClick={onClose}
          >
            ×
          </button>
        </div>

        <p className="muted" style={{ margin: '8px 0 16px 0', fontSize: '0.9rem' }}>
          Upload a CSV in the template format. Each valid row is priced by Pricing and booked as a merchant order; you can review rows before importing.
        </p>

        {fileError && (
          <div className="feedback-banner is-error" role="alert" style={{ marginBottom: '16px' }}>
            <span>⚠️ {fileError}</span>
          </div>
        )}

        {!file && (
          <div
            className={`file-dropzone-box ${isDragOver ? 'is-drag-over' : ''}`}
            style={{
              padding: '32px 16px',
              border: '2px dashed var(--sd-border, #cbd5e1)',
              borderRadius: '12px',
              textAlign: 'center',
              background: isDragOver ? 'var(--sd-surface-card, #f8fafc)' : 'transparent',
              cursor: 'pointer',
            }}
            onDragOver={(e: DragEvent<HTMLDivElement>) => {
              e.preventDefault()
              setIsDragOver(true)
            }}
            onDragLeave={() => setIsDragOver(false)}
            onDrop={(e: DragEvent<HTMLDivElement>) => {
              e.preventDefault()
              setIsDragOver(false)
              if (e.dataTransfer.files?.[0]) handleFileSelected(e.dataTransfer.files[0])
            }}
            onClick={() => fileInputRef.current?.click()}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv"
              style={{ display: 'none' }}
              onChange={(e: ChangeEvent<HTMLInputElement>) => {
                if (e.target.files?.[0]) handleFileSelected(e.target.files[0])
              }}
            />
            <div style={{ fontSize: '2rem', marginBottom: '8px' }}>📄</div>
            <p style={{ fontWeight: 600, margin: '0 0 4px 0' }}>Drop your CSV file here, or browse</p>
            <p className="muted" style={{ fontSize: '0.82rem', margin: 0 }}>Template format, up to 500 rows and 1 MB</p>
            <div style={{ marginTop: '16px' }}>
              <Button type="button" variant="secondary" size="sm">
                Choose CSV File
              </Button>
            </div>
          </div>
        )}

        {file && (
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
              <div>
                <strong>{file.name}</strong> · {rows.length} rows parsed
              </div>
              <div style={{ display: 'flex', gap: '8px' }}>
                <Badge variant={hasErrors ? 'warning' : 'success'}>
                  {validRows.length} valid / {rows.length} total
                </Badge>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={importing}
                  onClick={reset}
                >
                  Change File
                </Button>
              </div>
            </div>

            <div style={{ maxHeight: '220px', overflowY: 'auto', border: '1px solid var(--sd-border, #e2e8f0)', borderRadius: '8px' }}>
              <table style={{ width: '100%', fontSize: '0.85rem', borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0', textAlign: 'left' }}>
                    <th style={{ padding: '6px 8px' }}>#</th>
                    <th style={{ padding: '6px 8px' }}>Reference</th>
                    <th style={{ padding: '6px 8px' }}>Route</th>
                    <th style={{ padding: '6px 8px' }}>Weight</th>
                    <th style={{ padding: '6px 8px' }}>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(0, 50).map((r) => (
                    <tr key={r.rowNumber} style={{ borderBottom: '1px solid #f1f5f9' }}>
                      <td style={{ padding: '6px 8px' }}>{r.rowNumber}</td>
                      <td style={{ padding: '6px 8px' }}>{r.order.merchantReference || '—'}</td>
                      <td style={{ padding: '6px 8px' }}>{r.order.pickupZone || '?'} → {r.order.dropoffZone || '?'}</td>
                      <td style={{ padding: '6px 8px' }}>{Number.isFinite(r.order.weightKg) ? `${r.order.weightKg} kg` : '—'}</td>
                      <td style={{ padding: '6px 8px' }}>
                        {r.error ? (
                          <span style={{ color: '#dc2626' }}>{r.error}</span>
                        ) : outcomes[r.rowNumber]?.state === 'booked' ? (
                          <span style={{ color: '#16a34a' }}>✓ Booked {(outcomes[r.rowNumber] as { trackingNumber: string }).trackingNumber}</span>
                        ) : outcomes[r.rowNumber] ? (
                          <span style={{ color: '#dc2626' }}>{(outcomes[r.rowNumber] as { message: string }).message}</span>
                        ) : (
                          <span>Ready</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {rows.length > 50 && (
                <p className="muted" style={{ textAlign: 'center', padding: '6px', margin: 0, fontSize: '0.8rem' }}>
                  Showing first 50 of {rows.length} rows
                </p>
              )}
            </div>
          </div>
        )}

        {(progress || bookedCount > 0) && (
          <div className="feedback-banner is-info" role="status" style={{ marginTop: '16px', fontSize: '0.85rem' }}>
            <span>{progress ?? `${bookedCount} of ${validRows.length} valid orders booked.${hasErrors ? ' Rows with errors were skipped; fix them in the file and import again.' : ''}`}</span>
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '24px' }}>
          <Button type="button" variant="ghost" size="sm" onClick={handleDownloadSample}>
            📥 Download Sample Template
          </Button>

          <div style={{ display: 'flex', gap: '8px' }}>
            <Button type="button" variant="secondary" onClick={() => { cancelled.current = true; onClose() }}>
              {importing ? 'Stop after current order' : 'Cancel'}
            </Button>
            <Button
              type="button"
              variant="primary"
              disabled={importing || pendingRows.length === 0}
              isLoading={importing}
              onClick={() => void handleImport()}
            >
              {bookedCount > 0 && pendingRows.length > 0 ? `Retry ${pendingRows.length} remaining` : `Import ${pendingRows.length} orders`}
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}

