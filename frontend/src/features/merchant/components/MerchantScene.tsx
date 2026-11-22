import { useState } from 'react'
import { Button } from '../../../components/ui/Button'
import { ArrowUpRightIcon, PauseIcon, PlayIcon } from '../../../components/ui/icons'
import './MerchantScene.css'

/** Stages shown on the order card; each crossfades in step with the scene (see MerchantScene.css). */
const STAGES = ['Order received', 'Packed & labelled', 'Courier picked up', 'Out for delivery', 'Delivered'] as const

/** A brand parcel, drawn small. Origin is the bottom-centre so it can sit on any surface. */
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
      <g className="ms-wheel">
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
      <g className="ms-speed">
        <path className="ms-line ms-line-soft" d="M-8 -36H-24M-6 -26H-17M-10 -17H-28" />
      </g>
      <g className="ms-van-body">
        <rect x="0" y="-50" width="60" height="40" rx="5" fill="#1f3d33" />
        <path d="M58 -42H76C80 -42 83 -40.5 85 -37.5L92 -26C93.5 -23.5 94 -21 94 -18V-14C94 -11.8 92.2 -10 90 -10H58Z" fill="#1f3d33" />
        <path d="M64 -38H75.5C78 -38 79.8 -37 81 -35L86.5 -26H64Z" fill="#dfe9c4" />
        <rect x="0" y="-25" width="92" height="3.5" fill="#c8e06a" />
        <text x="8" y="-33" className="ms-van-logo">SwiftDrop.</text>
        <rect x="-2" y="-13" width="98" height="4" rx="2" fill="#16302a" />
        <rect x="91.5" y="-20" width="3" height="3.2" rx="1" fill="#c8e06a" />
        <rect x="-1.5" y="-22" width="2.5" height="5" rx="1" fill="#d9775c" />
        <Wheel x={18} />
        <Wheel x={76} />
      </g>
    </>
  )
}

/** The merchant's shop: scalloped awning, a display window of stock and a hanging "Open" sign. */
function Storefront() {
  const scallops = Array.from({ length: 7 }, (_, index) => index)
  const width = 104 / 7
  return (
    <g>
      <rect x="14" y="124" width="104" height="82" rx="2" fill="#fbfaf3" />
      <rect x="10" y="108" width="112" height="18" rx="3" fill="#1f3d33" />
      <text x="66" y="120.5" className="ms-sign">YOUR STORE</text>
      {scallops.map((index) => (
        <rect key={`a${index}`} x={14 + index * width} y="126" width={width} height="9" fill={index % 2 ? '#f7f5ee' : '#c8e06a'} />
      ))}
      {scallops.map((index) => (
        <circle key={`c${index}`} cx={14 + (index + 0.5) * width} cy="135" r={width / 2} fill={index % 2 ? '#f7f5ee' : '#c8e06a'} />
      ))}

      {/* Display window with shelves of stock. */}
      <rect x="22" y="150" width="50" height="38" rx="2" fill="#e7efcc" />
      <path className="ms-line ms-line-shelf" d="M22 166H72M22 180H72" />
      <rect x="27" y="157" width="9" height="9" rx="1" fill="#b88754" />
      <rect x="38" y="160" width="7" height="6" rx="1" fill="#d9775c" />
      <rect x="47" y="156" width="10" height="10" rx="1" fill="#2b5244" />
      <rect x="59" y="159" width="8" height="7" rx="1" fill="#b88754" />
      <rect x="28" y="172" width="12" height="8" rx="1" fill="#2b5244" />
      <rect x="43" y="173" width="8" height="7" rx="1" fill="#c8e06a" />
      <rect x="54" y="171" width="12" height="9" rx="1" fill="#b88754" />
      <rect x="20" y="188" width="54" height="3" rx="1" fill="#1f3d33" />

      {/* Door with a hanging sign. */}
      <rect x="82" y="150" width="28" height="56" rx="2" fill="#2b5244" />
      <rect x="86" y="156" width="20" height="22" rx="1.5" fill="#335c4d" />
      <circle cx="105" cy="186" r="1.6" fill="#c8e06a" />
      <g className="ms-open-sign">
        <path className="ms-line ms-line-string" d="M90 158L96 152L102 158" />
        <rect x="87" y="158" width="18" height="8" rx="1.5" fill="#c8e06a" />
        <text x="96" y="164.2" className="ms-open-text">OPEN</text>
      </g>
      <rect x="10" y="205" width="112" height="3" rx="1" fill="#cfd5bf" />
    </g>
  )
}

/** A customer's home on the far side of town, with a map pin that confirms the drop. */
function Destination() {
  return (
    <g>
      <rect x="352" y="132" width="8" height="18" fill="#1f3d33" />
      <rect x="306" y="154" width="64" height="52" fill="#fbfaf3" />
      <polygon points="300,156 338,126 376,156" fill="#1f3d33" />
      <rect x="314" y="164" width="12" height="12" rx="1.5" fill="#e7efcc" />
      <rect x="342" y="174" width="18" height="32" rx="2" fill="#2b5244" />
      <circle cx="356" cy="191" r="1.4" fill="#c8e06a" />
      <circle cx="312" cy="202" r="6" fill="#a6b97a" />
      <circle cx="372" cy="202" r="5.5" fill="#b7c78d" />
    </g>
  )
}

export function MerchantScene() {
  const [paused, setPaused] = useState(false)

  return (
    <div className="merchant-scene" data-paused={paused}>
      <div
        className="ms-stage"
        role="img"
        aria-label="Illustration: a new order arrives at your store through the merchant API, is packed and labelled, a SwiftDrop van collects it and drives it to the customer, and a delivery webhook flies back to your store, while an order card moves from Order received to Delivered."
      >
        <div className="ms-cloud ms-cloud-one" />
        <div className="ms-cloud ms-cloud-two" />

        <div className="ms-order-card glass">
          <div className="ms-order-head"><span className="ms-live-dot" />Sample order</div>
          <div className="ms-order-number">ORD-10482 · via API</div>
          <div className="ms-order-status">
            {STAGES.map((stage, index) => <span key={stage} className={`ms-stage-label stage-${index}`}>{stage}</span>)}
          </div>
          <div className="ms-order-progress">
            {[1, 2, 3, 4].map((step) => <span key={step} className={`ms-progress-step step-${step}`} />)}
          </div>
        </div>

        <div className="ms-sticker"><ArrowUpRightIcon /><span className="ms-sticker-text">Ready to<br /><strong>ship.</strong></span></div>

        <svg className="ms-svg" viewBox="0 0 400 260" preserveAspectRatio="xMidYMax meet" aria-hidden="true" focusable="false">
          {/* Street: drawn far past the viewBox so wide cards never show an edge. */}
          <rect x="-700" y="206" width="1800" height="8" fill="#e6e9da" />
          <rect x="-700" y="214" width="1800" height="34" fill="#d5dbc4" />
          <path className="ms-line ms-line-road" d="M-700 231H1100" />
          <rect x="-700" y="248" width="1800" height="60" fill="#e3e7d3" />

          {/* Delivery route from the store to the customer, and the webhook that rides it back. */}
          <path className="ms-line ms-route-base" d="M66 96C130 10 270 6 338 92" />
          <path className="ms-line ms-route-progress" d="M66 96C130 10 270 6 338 92" pathLength={1} />
          <path className="ms-line ms-webhook-trail" d="M338 92C270 6 130 10 66 96" pathLength={1} />

          {/* Distant skyline for depth, kept low-contrast so the story stays in front. */}
          <g fill="#e3e7d6">
            <rect x="140" y="160" width="22" height="46" rx="2" />
            <rect x="164" y="142" width="18" height="64" rx="2" />
            <rect x="184" y="168" width="28" height="38" rx="2" />
            <rect x="216" y="150" width="20" height="56" rx="2" />
            <rect x="270" y="172" width="16" height="34" rx="2" />
            <rect x="-20" y="164" width="26" height="42" rx="2" />
          </g>
          <circle cx="66" cy="96" r="3" fill="#1f3d33" />

          <Storefront />
          <g transform="translate(258 0)">
            <rect x="-2" y="180" width="4" height="26" fill="#8a7a5a" />
            <circle cx="0" cy="172" r="13" fill="#b7c78d" />
            <circle cx="-8" cy="180" r="8.5" fill="#a6b97a" />
            <circle cx="8" cy="180" r="8.5" fill="#a6b97a" />
          </g>
          <Destination />

          {/* New order lands in the shop from the merchant API. */}
          <g transform="translate(66 60)">
            <g className="ms-order-ticket">
              <rect x="-33" y="-11" width="66" height="22" rx="11" fill="#1f3d33" />
              <text x="0" y="3" className="ms-ticket-text">POST /orders</text>
            </g>
          </g>

          {/* Packed parcel waits on the step, then rides behind the van so it disappears into it. */}
          <g transform="translate(57 206)"><g className="ms-parcel"><MiniParcel /></g></g>

          <g transform="translate(96 236)"><g className="ms-van"><Van /></g></g>

          {/* Delivered: the pin confirms the drop. */}
          <g transform="translate(338 92)">
            <circle className="ms-ripple" r="11" fill="none" />
            <g className="ms-check">
              <circle r="11" fill="#c8e06a" stroke="#ffffff" strokeWidth="2" />
              <path className="ms-line ms-check-mark" d="M-4.5 0.5 -1.2 3.8 5 -3" />
            </g>
          </g>

          {/* The webhook lands back at the store. */}
          <g transform="translate(70 72)">
            <g className="ms-webhook-chip">
              <rect x="-46" y="-11" width="92" height="22" rx="11" fill="#ffffff" stroke="#c8e06a" strokeWidth="1.5" />
              <circle cx="-33" cy="0" r="5.5" fill="#c8e06a" />
              <path className="ms-line ms-chip-mark" d="M-35.6 0 -33.8 1.8 -30.4 -1.6" />
              <text x="6" y="3" className="ms-chip-text">webhook · 200</text>
            </g>
          </g>
        </svg>
      </div>

      <Button
        variant="secondary"
        size="icon-sm"
        className="ms-toggle"
        aria-label={paused ? 'Play merchant animation' : 'Pause merchant animation'}
        onClick={() => setPaused((value) => !value)}
      >
        {paused ? <PlayIcon /> : <PauseIcon />}
      </Button>
    </div>
  )
}
