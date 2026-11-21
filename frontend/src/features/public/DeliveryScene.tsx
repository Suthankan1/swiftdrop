import { useState } from 'react'
import { Button } from '../../components/ui/Button'
import { ArrowUpRightIcon, PauseIcon, PlayIcon } from '../../components/ui/icons'
import './DeliveryScene.css'

/** Stages shown on the tracking card; each crossfades in step with the scene (see DeliveryScene.css). */
const STAGES = ['Booked', 'Picked up', 'In transit', 'Out for delivery', 'Delivered'] as const

/** The brand parcel, drawn small. Origin is the bottom-centre so it can sit on any surface. */
function MiniParcel() {
  return (
    <>
      <polygon points="-8,-14 -5,-18 11,-18 8,-14" fill="#c69a69" />
      <polygon points="8,-14 11,-18 11,-4 8,0" fill="#9b6b3e" />
      <rect x="-8" y="-14" width="16" height="14" rx="1" fill="#b88754" />
      <rect x="-1.5" y="-14" width="3" height="14" fill="#d1a976" />
      <rect x="-6.5" y="-7.5" width="5" height="4" rx="0.6" fill="#efeedb" />
    </>
  )
}

function Wheel({ x }: { x: number }) {
  return (
    <g transform={`translate(${x} -8)`}>
      <g className="scene-wheel">
        <circle r="8" fill="#16302a" />
        <circle r="3.6" fill="#dfe4cf" />
        <rect x="-3.6" y="-0.75" width="7.2" height="1.5" fill="#16302a" />
      </g>
    </g>
  )
}

/** Side-view courier van. Origin is the rear wheel line (y = 0 is the road surface). */
function Van() {
  return (
    <>
      <ellipse cx="47" cy="1" rx="50" ry="3" fill="rgb(31 61 51 / 0.14)" />
      <g className="scene-speed">
        <path className="scene-line scene-line-soft" d="M-8 -36H-24M-6 -26H-17M-10 -17H-28" />
      </g>
      <g className="scene-van-body">
        <rect x="0" y="-50" width="60" height="40" rx="5" fill="#1f3d33" />
        <path d="M58 -42H76C80 -42 83 -40.5 85 -37.5L92 -26C93.5 -23.5 94 -21 94 -18V-14C94 -11.8 92.2 -10 90 -10H58Z" fill="#1f3d33" />
        <path d="M64 -38H75.5C78 -38 79.8 -37 81 -35L86.5 -26H64Z" fill="#dfe9c4" />
        <rect x="0" y="-25" width="92" height="3.5" fill="#c8e06a" />
        <text x="8" y="-33" className="scene-van-logo">SwiftDrop.</text>
        <rect x="-2" y="-13" width="98" height="4" rx="2" fill="#16302a" />
        <rect x="91.5" y="-20" width="3" height="3.2" rx="1" fill="#c8e06a" />
        <rect x="-1.5" y="-22" width="2.5" height="5" rx="1" fill="#d9775c" />
        <Wheel x={18} />
        <Wheel x={76} />
      </g>
    </>
  )
}

function Hub() {
  const stripes = Array.from({ length: 6 }, (_, index) => index)
  return (
    <g>
      <rect x="22" y="124" width="92" height="82" rx="2" fill="#fbfaf3" />
      <rect x="18" y="112" width="100" height="14" rx="3" fill="#1f3d33" />
      <text x="68" y="122" className="scene-sign">SwiftDrop.</text>
      {stripes.map((index) => (
        <rect key={index} x={22 + index * (92 / 6)} y="130" width={92 / 6} height="11" fill={index % 2 ? '#f7f5ee' : '#c8e06a'} />
      ))}
      {stripes.map((index) => (
        <circle key={index} cx={22 + (index + 0.5) * (92 / 6)} cy="141" r={92 / 12} fill={index % 2 ? '#f7f5ee' : '#c8e06a'} />
      ))}
      <rect x="32" y="158" width="46" height="48" rx="1.5" fill="#2b5244" />
      <path className="scene-line scene-line-slat" d="M32 166H78M32 174H78M32 182H78M32 190H78M32 198H78" />
      <rect x="88" y="158" width="18" height="18" rx="2" fill="#e7efcc" />
      <circle cx="113" cy="202" r="6" fill="#a6b97a" />
    </g>
  )
}

function House() {
  return (
    <g>
      <rect x="348" y="110" width="10" height="22" fill="#1f3d33" />
      <rect x="286" y="140" width="82" height="66" fill="#fbfaf3" />
      <polygon points="278,142 327,104 376,142" fill="#1f3d33" />
      <rect x="292" y="152" width="14" height="14" rx="1.5" fill="#e7efcc" />
      <rect x="348" y="152" width="14" height="14" rx="1.5" fill="#e7efcc" />
      <rect x="312" y="164" width="28" height="42" rx="2" fill="#1f3d33" />
      <rect x="314" y="166" width="24" height="40" fill="#f1f6d6" />
      <g className="scene-customer">
        <circle cx="326" cy="176" r="4.6" fill="#1f3d33" />
        <rect x="319.5" y="181.5" width="13" height="24.5" rx="5.5" fill="#1f3d33" />
      </g>
      <g className="scene-door">
        <rect x="314" y="166" width="24" height="40" fill="#2b5244" />
        <rect x="317" y="170" width="18" height="14" rx="1" fill="#335c4d" />
        <rect x="317" y="188" width="18" height="14" rx="1" fill="#335c4d" />
        <circle cx="334" cy="187" r="1.6" fill="#c8e06a" />
      </g>
      <rect x="306" y="205" width="40" height="3" rx="1" fill="#cfd5bf" />
      <circle cx="292" cy="201" r="7" fill="#a6b97a" />
      <circle cx="302" cy="203" r="5" fill="#b7c78d" />
      <circle cx="362" cy="201" r="6.5" fill="#a6b97a" />
    </g>
  )
}

export function DeliveryScene() {
  const [paused, setPaused] = useState(false)

  return (
    <div className="hero-art delivery-scene" data-paused={paused}>
      <div
        className="scene-stage"
        role="img"
        aria-label="Illustration: a SwiftDrop van collects a parcel from the hub, drives it across town and hands it to a customer at their door, while a live tracking card moves from Booked to Delivered. A badge reads “Made to connect.”"
      >
        <div className="scene-cloud scene-cloud-one" />
        <div className="scene-cloud scene-cloud-two" />

        <div className="scene-track-card glass">
          <div className="scene-track-head"><span className="scene-live-dot" />Live tracking</div>
          <div className="scene-track-number">SWD-20261023-A1B2C3</div>
          <div className="scene-track-status">
            {STAGES.map((stage, index) => <span key={stage} className={`scene-stage-label stage-${index}`}>{stage}</span>)}
          </div>
          <div className="scene-track-progress">
            {[1, 2, 3, 4].map((step) => <span key={step} className={`scene-progress-step step-${step}`} />)}
          </div>
        </div>

        <div className="art-sticker"><ArrowUpRightIcon /><span className="art-sticker-text">Made to<br /><strong>connect.</strong></span></div>

        <svg className="scene-svg" viewBox="0 0 400 260" preserveAspectRatio="xMidYMax meet" aria-hidden="true" focusable="false">
          {/* Street: drawn far past the viewBox so wide cards never show an edge. */}
          <rect x="-700" y="206" width="1800" height="8" fill="#e6e9da" />
          <rect x="-700" y="214" width="1800" height="34" fill="#d5dbc4" />
          <path className="scene-line scene-line-road" d="M-700 231H1100" />
          <rect x="-700" y="248" width="1800" height="60" fill="#e3e7d3" />

          {/* Tracking route from the hub to the customer's door. */}
          <path className="scene-line scene-route-base" d="M68 90C126 -4 266 -10 327 80" />
          <path className="scene-line scene-route-progress" d="M68 90C126 -4 266 -10 327 80" pathLength={1} />

          {/* Distant skyline for depth, kept low-contrast so the story stays in front. */}
          <g fill="#e3e7d6">
            <rect x="128" y="158" width="22" height="48" rx="2" />
            <rect x="152" y="140" width="18" height="66" rx="2" />
            <rect x="172" y="166" width="28" height="40" rx="2" />
            <rect x="204" y="148" width="20" height="58" rx="2" />
            <rect x="258" y="170" width="16" height="36" rx="2" />
            <rect x="378" y="160" width="26" height="46" rx="2" />
          </g>
          <circle cx="68" cy="90" r="3" fill="#1f3d33" />
          <circle cx="327" cy="80" r="3" fill="#1f3d33" />

          <Hub />
          <g transform="translate(246 0)">
            <rect x="-2" y="178" width="4" height="28" fill="#8a7a5a" />
            <circle cx="0" cy="170" r="14" fill="#b7c78d" />
            <circle cx="-8" cy="178" r="9" fill="#a6b97a" />
            <circle cx="8" cy="178" r="9" fill="#a6b97a" />
          </g>
          <House />

          {/* Parcels sit behind the van so they disappear into it and emerge from it. */}
          <g transform="translate(57 206)"><g className="scene-parcel-pickup"><MiniParcel /></g></g>
          <g transform="translate(227 204)"><g className="scene-parcel-drop"><MiniParcel /></g></g>

          <g transform="translate(96 236)"><g className="scene-van"><Van /></g></g>

          <g transform="translate(372 132)">
            <circle className="scene-ripple" r="11" fill="none" />
            <g className="scene-check">
              <circle r="11" fill="#c8e06a" stroke="#ffffff" strokeWidth="2" />
              <path className="scene-line scene-check-mark" d="M-4.5 0.5 -1.2 3.8 5 -3" />
            </g>
          </g>
        </svg>
      </div>

      <Button
        variant="secondary"
        size="icon-sm"
        className="scene-toggle"
        aria-label={paused ? 'Play delivery animation' : 'Pause delivery animation'}
        onClick={() => setPaused((value) => !value)}
      >
        {paused ? <PlayIcon /> : <PauseIcon />}
      </Button>
    </div>
  )
}
