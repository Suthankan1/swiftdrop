import type { ReactNode, SVGProps } from 'react'

type IconProps = Omit<SVGProps<SVGSVGElement>, 'children'> & { size?: number }

function Icon({ size = 18, className = '', children, ...rest }: IconProps & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={`icon ${className}`.trim()}
      {...rest}
    >
      {children}
    </svg>
  )
}

export function ArrowRightIcon(props: IconProps) {
  return <Icon {...props} className={`icon-arrow-right ${props.className ?? ''}`}><path d="M5 12h14M13 6l6 6-6 6" /></Icon>
}

export function ArrowUpRightIcon(props: IconProps) {
  return <Icon {...props} className={`icon-arrow-up-right ${props.className ?? ''}`}><path d="M7 17 17 7M8 7h9v9" /></Icon>
}

export function SearchIcon(props: IconProps) {
  return <Icon {...props}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.2-4.2" /></Icon>
}

export function UserIcon(props: IconProps) {
  return <Icon {...props}><circle cx="12" cy="8" r="3.75" /><path d="M4.5 20c1.4-3.4 4.2-5 7.5-5s6.1 1.6 7.5 5" /></Icon>
}

export function StoreIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4 10.5V19a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-8.5" />
      <path d="M3 10.5 4.7 5a1.4 1.4 0 0 1 1.3-1h12a1.4 1.4 0 0 1 1.3 1l1.7 5.5H3Z" />
      <path d="M10 20v-5h4v5" />
    </Icon>
  )
}

export function TruckIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M14 16.5V6H3v10.5h1.5" />
      <path d="M14 9h4l3 3.5v4h-1.5" />
      <path d="M9.5 16.5h5.5" />
      <circle cx="7" cy="17" r="2" />
      <circle cx="17" cy="17" r="2" />
    </Icon>
  )
}

export function SparkleIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M11 3.5 12.7 8.3 17.5 10l-4.8 1.7L11 16.5l-1.7-4.8L4.5 10l4.8-1.7Z" fill="currentColor" />
      <path d="M18.5 14.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7Z" fill="currentColor" />
    </Icon>
  )
}

export function PauseIcon(props: IconProps) {
  return <Icon {...props}><path d="M9 6v12M15 6v12" /></Icon>
}

export function PlayIcon(props: IconProps) {
  return <Icon {...props}><path d="M8 5.5v13l10.5-6.5Z" /></Icon>
}
