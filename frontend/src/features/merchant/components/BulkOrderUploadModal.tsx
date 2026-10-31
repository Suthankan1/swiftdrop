import { useState, useRef, type ChangeEvent, type DragEvent } from 'react'
import { Button } from '../../../components/ui/Button'
import { Badge } from '../../../components/ui/Badge'

export interface BulkOrderUploadModalProps {
  isOpen: boolean
  onClose: () => void
  onImportComplete?: (count: number) => void
}

interface ParsedRow {
  rowNumber: number
  pickupAddress: string
  dropoffAddress: string
  weightKg: number
  dimensions: string
  error?: string
}

const SAMPLE_CSV = `Pickup Address,Dropoff Address,Weight (kg),Dimensions (cm)
12 Vauxhall St Colombo 02,45 Station Rd Negombo,2.5,20x15x10
Warehouse 4 Colombo 01,88 Galle Rd Kalutara,1.2,15x10x5
`

export function BulkOrderUploadModal({ isOpen, onClose, onImportComplete }: BulkOrderUploadModalProps) {
  const [file, setFile] = useState<File | null>(null)
  const [rows, setRows] = useState<ParsedRow[]>([])
  const [isProcessing, setIsProcessing] = useState(false)
  const [isSuccess, setIsSuccess] = useState(false)
  const [isDragOver, setIsDragOver] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  if (!isOpen) return null

  const validRows = rows.filter((r) => !r.error)
  const hasErrors = rows.some((r) => r.error)

  function handleFileSelected(selectedFile: File) {
    if (!selectedFile.name.endsWith('.csv')) {
      alert('Please upload a .csv file.')
      return
    }
    setFile(selectedFile)
    const reader = new FileReader()
    reader.onload = (e) => {
      const text = e.target?.result as string
      if (!text) return
      const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0)
      if (lines.length <= 1) {
        setRows([])
        return
      }

      const parsed: ParsedRow[] = []
      // Skip header row
      for (let i = 1; i < lines.length; i++) {
        const line = lines[i]
        if (!line) continue
        const parts = line.split(',').map((p) => p.trim())
        const pickup = parts[0] || ''
        const dropoff = parts[1] || ''
        const weight = parseFloat(parts[2] || '0')
        const dimensions = parts[3] || '20x15x10'

        let error: string | undefined
        if (!pickup) error = 'Missing pickup address'
        else if (!dropoff) error = 'Missing dropoff address'
        else if (isNaN(weight) || weight <= 0 || weight > 50) error = 'Weight must be between 0.1 and 50 kg'

        parsed.push({
          rowNumber: i,
          pickupAddress: pickup,
          dropoffAddress: dropoff,
          weightKg: isNaN(weight) ? 0 : weight,
          dimensions,
          error,
        })
      }
      setRows(parsed)
    }
    reader.readAsText(selectedFile)
  }

  function handleDownloadSample() {
    const blob = new Blob([SAMPLE_CSV], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'swiftdrop-bulk-orders-sample.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  function handleConfirmImport() {
    setIsProcessing(true)
    setTimeout(() => {
      setIsProcessing(false)
      setIsSuccess(true)
      onImportComplete?.(validRows.length)
      setTimeout(() => {
        setIsSuccess(false)
        setFile(null)
        setRows([])
        onClose()
      }, 1500)
    }, 1000)
  }

  return (
    <div className="modal-backdrop" role="presentation" onClick={onClose}>
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
            disabled={isProcessing}
          >
            ×
          </button>
        </div>

        <p className="muted" style={{ margin: '8px 0 16px 0', fontSize: '0.9rem' }}>
          Upload a structured CSV file to book multiple parcels at once. Each valid row will be validated against active zone tariffs.
        </p>

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
            <p className="muted" style={{ fontSize: '0.82rem', margin: 0 }}>Supports .csv format up to 500 rows</p>
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
                  onClick={() => {
                    setFile(null)
                    setRows([])
                  }}
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
                    <th style={{ padding: '6px 8px' }}>Pickup</th>
                    <th style={{ padding: '6px 8px' }}>Dropoff</th>
                    <th style={{ padding: '6px 8px' }}>Weight</th>
                    <th style={{ padding: '6px 8px' }}>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(0, 10).map((r) => (
                    <tr key={r.rowNumber} style={{ borderBottom: '1px solid #f1f5f9' }}>
                      <td style={{ padding: '6px 8px' }}>{r.rowNumber}</td>
                      <td style={{ padding: '6px 8px' }}>{r.pickupAddress || '—'}</td>
                      <td style={{ padding: '6px 8px' }}>{r.dropoffAddress || '—'}</td>
                      <td style={{ padding: '6px 8px' }}>{r.weightKg} kg</td>
                      <td style={{ padding: '6px 8px' }}>
                        {r.error ? (
                          <span style={{ color: '#dc2626' }}>{r.error}</span>
                        ) : (
                          <span style={{ color: '#16a34a' }}>✓ Ready</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {rows.length > 10 && (
                <p className="muted" style={{ textAlign: 'center', padding: '6px', margin: 0, fontSize: '0.8rem' }}>
                  Showing first 10 of {rows.length} rows
                </p>
              )}
            </div>
          </div>
        )}

        {isSuccess && (
          <div className="feedback-banner is-success" role="status" style={{ marginTop: '16px' }}>
            <span>✓ Successfully imported and scheduled {validRows.length} parcels!</span>
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '24px' }}>
          <Button type="button" variant="ghost" size="sm" onClick={handleDownloadSample}>
            📥 Download Sample Template
          </Button>

          <div style={{ display: 'flex', gap: '8px' }}>
            <Button type="button" variant="secondary" onClick={onClose} disabled={isProcessing}>
              Cancel
            </Button>
            <Button
              type="button"
              variant="primary"
              disabled={!file || validRows.length === 0 || isProcessing || isSuccess}
              isLoading={isProcessing}
              loadingText="Importing..."
              onClick={handleConfirmImport}
            >
              Import {validRows.length > 0 ? `${validRows.length} Orders` : 'Orders'}
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}

