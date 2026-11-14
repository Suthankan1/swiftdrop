import { Link } from 'react-router'

export interface BackButtonProps {
  to: string
  label: string
  className?: string
  onClick?: () => void
}

export function BackButton({ to, label, className = '', onClick }: BackButtonProps) {
  return (
    <Link
      to={to}
      onClick={onClick}
      className={`sd-back-button ${className}`.trim()}
      aria-label={label}
    >
      <svg
        className="sd-back-button-icon"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <line x1="19" y1="12" x2="5" y2="12" />
        <polyline points="12 19 5 12 12 5" />
      </svg>
      <span>{label}</span>
    </Link>
  )
}
