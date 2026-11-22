/**
 * Single source of truth for button class names, shared by <Button> (a native button) and <ButtonLink>
 * (a router link that must look like a button). Visual rules live in styles/global.css under "Button system".
 */
export type ButtonVariant =
  | 'primary'
  | 'secondary'
  | 'accent'
  | 'link'
  | 'light'
  | 'tonal'
  | 'ghost'
  | 'danger'
  | 'danger-ghost'

export type ButtonSize = 'sm' | 'md' | 'lg' | 'icon' | 'icon-sm'

export interface ButtonStyleOptions {
  variant?: ButtonVariant
  size?: ButtonSize
  fullWidth?: boolean
  isLoading?: boolean
  className?: string
}

export function buttonClassName({
  variant = 'primary',
  size = 'md',
  fullWidth = false,
  isLoading = false,
  className = '',
}: ButtonStyleOptions = {}): string {
  return [
    'button',
    `button-${variant}`,
    size !== 'md' ? `button-${size}` : '',
    fullWidth ? 'button-full' : '',
    isLoading ? 'is-loading' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ')
}
