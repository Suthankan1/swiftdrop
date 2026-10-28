import {
  ALLOWED_PROOF_MIME_TYPES,
  MAX_DELIVERY_NOTE_LENGTH,
  MAX_PROOF_SIZE_BYTES,
  MAX_RECIPIENT_NAME_LENGTH,
  type FileValidationResult,
} from '../api/proofContracts'

/**
 * Validates a candidate evidence file against approved platform proof policy.
 * MIME types: image/jpeg, image/png, image/webp.
 * Size bounds: > 0 bytes and <= 5 MiB (5,242,880 bytes).
 */
export function validateProofFile(file: unknown): FileValidationResult {
  if (!file) {
    return {
      valid: false,
      error: 'No file selected. Please choose a proof photo.',
    }
  }

  if (!(file instanceof File)) {
    return {
      valid: false,
      error: 'Invalid file object selected.',
    }
  }

  if (file.size === 0) {
    return {
      valid: false,
      error: 'Selected file is empty (0 bytes). Please capture or choose a valid image.',
    }
  }

  if (file.size > MAX_PROOF_SIZE_BYTES) {
    const sizeMb = (file.size / (1024 * 1024)).toFixed(2)
    return {
      valid: false,
      error: `File size (${sizeMb} MiB) exceeds the maximum allowed limit of 5 MiB.`,
    }
  }

  const mime = file.type ? file.type.toLowerCase().trim() : ''
  const isAllowed = ALLOWED_PROOF_MIME_TYPES.some((allowed) => mime === allowed)

  if (!isAllowed) {
    return {
      valid: false,
      error: 'Unsupported file format. Only JPEG, PNG, and WebP photos are permitted.',
    }
  }

  return { valid: true }
}

/**
 * Validates optional recipient name length against schema bounds (max 128 chars).
 */
export function validateRecipientName(name?: string | null): { valid: boolean; error?: string } {
  if (!name) return { valid: true }
  if (name.length > MAX_RECIPIENT_NAME_LENGTH) {
    return {
      valid: false,
      error: `Recipient name cannot exceed ${MAX_RECIPIENT_NAME_LENGTH} characters (currently ${name.length}).`,
    }
  }
  return { valid: true }
}

/**
 * Validates optional delivery note length against schema bounds (max 512 chars).
 */
export function validateDeliveryNote(note?: string | null): { valid: boolean; error?: string } {
  if (!note) return { valid: true }
  if (note.length > MAX_DELIVERY_NOTE_LENGTH) {
    return {
      valid: false,
      error: `Delivery note cannot exceed ${MAX_DELIVERY_NOTE_LENGTH} characters (currently ${note.length}).`,
    }
  }
  return { valid: true }
}

/**
 * Human-readable byte formatting helper.
 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}
