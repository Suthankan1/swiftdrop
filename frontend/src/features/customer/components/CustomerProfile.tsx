import { useContext, useState } from 'react'
import type { CurrentUserProfile } from '../api/contracts'
import { createAccountsApi } from '../api/accounts'
import { FeedbackState } from '../../../components/ui/FeedbackState'
import { Button } from '../../../components/ui/Button'
import { AuthContext } from '../../../auth/context'

export type ProfileState =
  | { status: 'auth-required' | 'loading' | 'empty' | 'unavailable' | 'forbidden' }
  | { status: 'ready'; profile: CurrentUserProfile }

export function CustomerProfile({ state, onProfileUpdated }: { state: ProfileState; onProfileUpdated?: () => void }) {
  const auth = useContext(AuthContext)

  const [editing, setEditing] = useState(false)
  const [fullName, setFullName] = useState('')
  const [phone, setPhone] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  if (state.status === 'auth-required') {
    return <FeedbackState title="Your profile, kept private">Sign-in is not available yet. Your personal details will appear here once secure sign-in is connected.</FeedbackState>
  }
  if (state.status === 'loading') {
    return <FeedbackState title="Loading your profile" tone="loading">Please wait while we retrieve your account details.</FeedbackState>
  }
  if (state.status === 'empty') {
    return <FeedbackState title="No profile to show">Your account details are not available yet. Please try again later.</FeedbackState>
  }
  if (state.status === 'unavailable') {
    return <FeedbackState title="Profile temporarily unavailable" tone="error">We could not reach the profile service. Please try again later.</FeedbackState>
  }
  if (state.status === 'forbidden') {
    return <FeedbackState title="Profile access unavailable" tone="error">This profile cannot be displayed for the current session.</FeedbackState>
  }

  if (state.status !== 'ready') return null
  const { profile } = state
  const createdAt = new Date(profile.createdAt)

  const openEdit = () => {
    setFullName(profile.fullName || '')
    setPhone(profile.phoneNumber || '')
    setError(null)
    setSuccess(null)
    setEditing(true)
  }

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!fullName.trim()) {
      setError('Full name is required.')
      return
    }
    if (phone.trim() && !/^\+?[0-9\s\-()]{7,25}$/.test(phone.trim())) {
      setError('Phone number must be 7 to 25 digits, spaces, or dashes (optionally starting with +).')
      return
    }
    if (!auth) {
      setEditing(false)
      return
    }
    setPending(true)
    setError(null)
    try {
      const api = createAccountsApi({ getAccessToken: auth.getAccessToken, fetch: globalThis.fetch })
      await api.updateCurrentUser({ fullName: fullName.trim(), phoneNumber: phone.trim() })
      setSuccess('Profile updated successfully!')
      setEditing(false)
      onProfileUpdated?.()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to save profile changes.')
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="card" aria-labelledby="profile-heading">
      <div className="section-heading" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '8px' }}>
        <div>
          <p className="eyebrow">Account details</p>
          <h2 id="profile-heading">{profile.fullName}</h2>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
          <span className="badge">{profile.roles.join(' · ') || 'No roles assigned'}</span>
          {!editing && auth && (
            <Button variant="secondary" size="sm" onClick={openEdit} aria-label="Edit contact details">
              Edit details
            </Button>
          )}
        </div>
      </div>

      {success && <p role="status" style={{ color: 'var(--color-primary, #15803d)', fontWeight: 600, fontSize: '0.85rem', margin: '8px 0' }}>{success}</p>}
      {error && <p role="alert" style={{ color: 'var(--color-danger, #b91c1c)', fontWeight: 600, fontSize: '0.85rem', margin: '8px 0' }}>{error}</p>}

      {editing ? (
        <form onSubmit={handleSave} style={{ marginTop: '16px', display: 'flex', flexDirection: 'column', gap: '12px' }} aria-label="Edit account details">
          <div>
            <label htmlFor="edit-profile-name" style={{ display: 'block', fontSize: '0.85rem', fontWeight: 600, marginBottom: '4px' }}>
              Full name
            </label>
            <input
              id="edit-profile-name"
              type="text"
              required
              maxLength={255}
              value={fullName}
              disabled={pending}
              onChange={e => setFullName(e.target.value)}
              style={{ width: '100%', padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--sd-border, #ccc)' }}
            />
          </div>
          <div>
            <label htmlFor="edit-profile-phone" style={{ display: 'block', fontSize: '0.85rem', fontWeight: 600, marginBottom: '4px' }}>
              Mobile phone number
            </label>
            <input
              id="edit-profile-phone"
              type="tel"
              placeholder="+94 77 123 4567"
              maxLength={25}
              value={phone}
              disabled={pending}
              onChange={e => setPhone(e.target.value)}
              style={{ width: '100%', padding: '8px 12px', borderRadius: '6px', border: '1px solid var(--sd-border, #ccc)' }}
            />
            <p className="muted" style={{ fontSize: '0.78rem', margin: '4px 0 0 0' }}>
              Used for courier delivery SMS and doorstep arrival notices.
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', marginTop: '8px' }}>
            <Button type="submit" variant="primary" size="sm" disabled={pending || !fullName.trim()}>
              {pending ? 'Saving…' : 'Save changes'}
            </Button>
            <Button type="button" variant="secondary" size="sm" disabled={pending} onClick={() => setEditing(false)}>
              Cancel
            </Button>
          </div>
        </form>
      ) : (
        <dl className="profile-details">
          <ProfileField label="Email" value={profile.email} icon="✉️" />
          <ProfileField label="Phone number" value={profile.phoneNumber || 'Not provided'} icon="📞" />
          <ProfileField label="Member since" value={Number.isNaN(createdAt.getTime()) ? 'Unavailable' : createdAt.toLocaleDateString('en-GB', { dateStyle: 'long', timeZone: 'UTC' })} icon="📅" />
        </dl>
      )}

      {profile.merchantProfile && (
        <section aria-labelledby="merchant-heading" style={{ marginTop: '24px' }}>
          <h3 id="merchant-heading">Merchant profile</h3>
          <dl className="profile-details">
            <ProfileField label="Business" value={profile.merchantProfile.businessName} icon="🏬" />
            <ProfileField label="Merchant ID" value={profile.merchantProfile.merchantId} icon="🆔" />
            <ProfileField label="Registration number" value={profile.merchantProfile.registrationNumber} icon="📋" />
            <ProfileField label="Approval status" value={profile.merchantProfile.status} icon="⚡" />
          </dl>
        </section>
      )}
    </section>
  )
}

function ProfileField({ label, value, icon }: { label: string; value: string; icon?: string }) {
  return (
    <div className="field">
      <dt className="field-label">
        {icon && <span aria-hidden="true" style={{ marginRight: '4px' }}>{icon}</span>}
        {label}
      </dt>
      <dd className="field-value">{value}</dd>
    </div>
  )
}
