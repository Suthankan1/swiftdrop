import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { buttonClassName, type ButtonSize, type ButtonVariant } from './buttonStyles'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  isLoading?: boolean
  loadingText?: string
  leftIcon?: ReactNode
  rightIcon?: ReactNode
  fullWidth?: boolean
}

function ButtonSpinner() {
  return (
    <svg
      className="btn-spinner"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      style={{ width: '1em', height: '1em', display: 'inline-block', flexShrink: 0 }}
    >
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" opacity="0.25" />
      <path
        d="M12 2a10 10 0 0 1 10 10"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  )
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    isLoading = false,
    loadingText,
    leftIcon,
    rightIcon,
    fullWidth = false,
    disabled = false,
    className = '',
    children,
    type = 'button',
    ...rest
  },
  ref,
) {
  const classes = buttonClassName({ variant, size, fullWidth, isLoading, className })

  return (
    <button
      ref={ref}
      type={type}
      className={classes}
      disabled={disabled || isLoading}
      aria-busy={isLoading ? 'true' : undefined}
      {...rest}
    >
      {isLoading ? (
        <>
          <ButtonSpinner />
          <span>{loadingText || children}</span>
        </>
      ) : (
        <>
          {leftIcon && <span className="button-icon-left" aria-hidden="true">{leftIcon}</span>}
          {children && <span className="button-label">{children}</span>}
          {rightIcon && <span className="button-icon-right" aria-hidden="true">{rightIcon}</span>}
        </>
      )}
    </button>
  )
})

