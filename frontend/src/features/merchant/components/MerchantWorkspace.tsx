import { Outlet } from 'react-router'
import { MerchantNav } from './MerchantNav'

export function MerchantWorkspace() {
  return (
    <div className="merchant-workspace">
      <div className="merchant-workspace-tabs">
        <div className="page-container">
          <MerchantNav />
        </div>
      </div>
      <Outlet />
    </div>
  )
}
