import { forwardRef, type ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'
import { buttonClassName, type ButtonSize, type ButtonVariant } from './buttonStyles'

export interface ButtonLinkProps extends LinkProps {
  variant?: ButtonVariant
  size?: ButtonSize
  fullWidth?: boolean
  leftIcon?: ReactNode
  rightIcon?: ReactNode
}

/** A router link rendered with the shared button system, for navigation that should look like an action. */
export const ButtonLink = forwardRef<HTMLAnchorElement, ButtonLinkProps>(function ButtonLink(
  { variant = 'primary', size = 'md', fullWidth = false, leftIcon, rightIcon, className = '', children, ...rest },
  ref,
) {
  return (
    <Link ref={ref} className={buttonClassName({ variant, size, fullWidth, className })} {...rest}>
      {leftIcon && <span className="button-icon-left" aria-hidden="true">{leftIcon}</span>}
      <span className="button-label">{children}</span>
      {rightIcon && <span className="button-icon-right" aria-hidden="true">{rightIcon}</span>}
    </Link>
  )
})
