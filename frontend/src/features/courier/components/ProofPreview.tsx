import { useEffect, useMemo } from 'react'
import { Button } from '../../../components/ui/Button'
import { formatBytes } from '../utils/proofValidation'

export interface ProofPreviewProps {
  source: File | Blob
  title?: string
  altText?: string
  fileName?: string
  fileSize?: number
  mimeType?: string
  onRemove?: () => void
  onClose?: () => void
  subject?: string | null
}

export function ProofPreview({
  source,
  title = 'Proof Evidence Preview',
  altText = 'Captured proof of delivery photo',
  fileName,
  fileSize,
  mimeType,
  onRemove,
  onClose,
}: ProofPreviewProps) {
  const objectUrl = useMemo(() => {
    return window.URL.createObjectURL(source)
  }, [source])


  useEffect(() => {
    return () => {
      window.URL.revokeObjectURL(objectUrl)
    }
  }, [objectUrl])

  const name = fileName || (source instanceof File ? source.name : 'proof-evidence.jpg')
  const size = fileSize !== undefined ? fileSize : source.size
  const type = mimeType || source.type || 'image/jpeg'

  return (
    <div className="proof-preview-card" role="region" aria-label={title}>
      <div className="proof-preview-header">
        <h3 className="proof-preview-title">{title}</h3>
        {onClose && (
          <Button
            variant="secondary"
            size="sm"
            onClick={onClose}
            aria-label="Close proof preview"
          >
            Close
          </Button>
        )}
      </div>

      <div className="proof-preview-image-wrap">
        {objectUrl ? (
          <img
            src={objectUrl}
            alt={altText}
            className="proof-preview-image"
          />
        ) : (
          <div className="proof-preview-placeholder" role="status">
            <span>Loading image...</span>
          </div>
        )}
      </div>

      <div className="proof-preview-metadata">
        <div className="proof-meta-item">
          <span className="proof-meta-label">File:</span>
          <span className="proof-meta-value">{name}</span>
        </div>
        <div className="proof-meta-item">
          <span className="proof-meta-label">Size:</span>
          <span className="proof-meta-value">{formatBytes(size)}</span>
        </div>
        <div className="proof-meta-item">
          <span className="proof-meta-label">Format:</span>
          <span className="proof-meta-value">{type}</span>
        </div>
      </div>

      {onRemove && (
        <div className="proof-preview-actions">
          <Button
            variant="secondary"
            size="sm"
            onClick={onRemove}
            aria-label="Remove selected proof image"
          >
            Remove & Select Different Photo
          </Button>
        </div>
      )}
    </div>
  )
}
