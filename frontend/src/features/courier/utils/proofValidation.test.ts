import { describe, expect, it } from 'vitest'
import {
  formatBytes,
  validateDeliveryNote,
  validateProofFile,
  validateRecipientName,
} from './proofValidation'

describe('Courier proof validation utilities', () => {
  it('rejects null, undefined, or missing file', () => {
    expect(validateProofFile(null)).toEqual({
      valid: false,
      error: 'No file selected. Please choose a proof photo.',
    })
    expect(validateProofFile(undefined)).toEqual({
      valid: false,
      error: 'No file selected. Please choose a proof photo.',
    })
  })

  it('rejects an empty (0 bytes) file', () => {
    const emptyFile = new File([], 'empty.jpg', { type: 'image/jpeg' })
    const result = validateProofFile(emptyFile)
    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/empty \(0 bytes\)/i)
  })

  it('accepts valid JPEG, PNG, and WebP files within 5 MiB', () => {
    const validJpeg = new File([new Uint8Array(1024)], 'photo.jpg', { type: 'image/jpeg' })
    expect(validateProofFile(validJpeg)).toEqual({ valid: true })

    const validPng = new File([new Uint8Array(2048)], 'photo.png', { type: 'image/png' })
    expect(validateProofFile(validPng)).toEqual({ valid: true })

    const validWebp = new File([new Uint8Array(512)], 'photo.webp', { type: 'image/webp' })
    expect(validateProofFile(validWebp)).toEqual({ valid: true })
  })

  it('accepts file exactly at 5 MiB boundary (5,242,880 bytes)', () => {
    const boundaryBuffer = new Uint8Array(5 * 1024 * 1024)
    const boundaryFile = new File([boundaryBuffer], 'boundary.jpg', { type: 'image/jpeg' })
    expect(validateProofFile(boundaryFile)).toEqual({ valid: true })
  })

  it('rejects oversized file exceeding 5 MiB', () => {
    const oversizedBuffer = new Uint8Array(5 * 1024 * 1024 + 1)
    const oversizedFile = new File([oversizedBuffer], 'huge.jpg', { type: 'image/jpeg' })
    const result = validateProofFile(oversizedFile)
    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/exceeds the maximum allowed limit of 5 MiB/i)
  })

  it('rejects unsupported MIME types such as pdf, gif, or text', () => {
    const pdf = new File([new Uint8Array(100)], 'doc.pdf', { type: 'application/pdf' })
    expect(validateProofFile(pdf).valid).toBe(false)
    expect(validateProofFile(pdf).error).toMatch(/unsupported file format/i)

    const gif = new File([new Uint8Array(100)], 'anim.gif', { type: 'image/gif' })
    expect(validateProofFile(gif).valid).toBe(false)

    const text = new File([new Uint8Array(100)], 'notes.txt', { type: 'text/plain' })
    expect(validateProofFile(text).valid).toBe(false)
  })

  describe('recipientName validation', () => {
    it('accepts undefined, null, or empty string', () => {
      expect(validateRecipientName()).toEqual({ valid: true })
      expect(validateRecipientName('')).toEqual({ valid: true })
      expect(validateRecipientName(null)).toEqual({ valid: true })
    })

    it('accepts names up to 128 characters', () => {
      const name128 = 'A'.repeat(128)
      expect(validateRecipientName(name128)).toEqual({ valid: true })
    })

    it('rejects names exceeding 128 characters', () => {
      const name129 = 'A'.repeat(129)
      const result = validateRecipientName(name129)
      expect(result.valid).toBe(false)
      expect(result.error).toMatch(/cannot exceed 128 characters/i)
    })
  })

  describe('deliveryNote validation', () => {
    it('accepts undefined, null, or empty string', () => {
      expect(validateDeliveryNote()).toEqual({ valid: true })
      expect(validateDeliveryNote('')).toEqual({ valid: true })
      expect(validateDeliveryNote(null)).toEqual({ valid: true })
    })

    it('accepts notes up to 512 characters', () => {
      const note512 = 'N'.repeat(512)
      expect(validateDeliveryNote(note512)).toEqual({ valid: true })
    })

    it('rejects notes exceeding 512 characters', () => {
      const note513 = 'N'.repeat(513)
      const result = validateDeliveryNote(note513)
      expect(result.valid).toBe(false)
      expect(result.error).toMatch(/cannot exceed 512 characters/i)
    })
  })

  describe('formatBytes helper', () => {
    it('formats bytes, KB, and MB accurately', () => {
      expect(formatBytes(500)).toBe('500 B')
      expect(formatBytes(1536)).toBe('1.5 KB')
      expect(formatBytes(2 * 1024 * 1024)).toBe('2.00 MB')
      expect(formatBytes(5242880)).toBe('5.00 MB')
    })
  })
})
