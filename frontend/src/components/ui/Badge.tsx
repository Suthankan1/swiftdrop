import type { HTMLAttributes, ReactNode } from 'react'

export type BadgeVariant =
  | 'primary'
  | 'brand'
  | 'success'
  | 'warning'
  | 'danger'
  | 'info'
  | 'neutral'
  | 'accent'

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant
  pulse?: boolean
  size?: 'sm' | 'md'
  icon?: ReactNode
}

export function Badge({
  variant = 'neutral',
  pulse = false,
  size = 'md',
  icon,
  className = '',
  children,
  ...rest
}: BadgeProps) {
  const classes = [
    'sd-badge',
    `sd-badge-${variant}`,
    size === 'sm' ? 'sd-badge-sm' : '',
    pulse ? 'has-pulse' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <span className={classes} {...rest}>
      {pulse && <span className="sd-badge-pulse-dot" aria-hidden="true" />}
      {icon && <span className="sd-badge-icon" aria-hidden="true">{icon}</span>}
      <span>{children}</span>
    </span>
  )
}

