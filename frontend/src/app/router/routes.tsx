import { Route, Routes } from 'react-router'
import { AppLayout } from '../../components/layout/AppLayout'
import { AdminPage } from '../../features/admin/pages/AdminPage'
import { CourierPage } from '../../features/courier/pages/CourierPage'
import { CustomerPage } from '../../features/customer/pages/CustomerPage'
import { DispatcherPage } from '../../features/dispatcher/pages/DispatcherPage'
import { MerchantPage } from '../../features/merchant/pages/MerchantPage'
import { MerchantWebhooksPage } from '../../features/merchant/pages/MerchantWebhooksPage'
import { LandingPage } from '../../features/public/LandingPage'
import { NotFoundPage } from '../../features/public/NotFoundPage'
import { LoginPage, CallbackPage, LogoutPage, ProtectedRoute } from '../../auth/AuthPages'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<LandingPage />} />
        <Route element={<ProtectedRoute role="CUSTOMER" />}><Route path="customer" element={<CustomerPage />} /></Route>
        <Route element={<ProtectedRoute role="MERCHANT" />}>
          <Route path="merchant" element={<MerchantPage />} />
          <Route path="merchant/webhooks" element={<MerchantWebhooksPage />} />
        </Route>
        <Route element={<ProtectedRoute role="COURIER" />}><Route path="courier" element={<CourierPage />} /></Route>
        <Route element={<ProtectedRoute role="DISPATCHER" />}><Route path="dispatcher" element={<DispatcherPage />} /></Route>
        <Route element={<ProtectedRoute role="ADMIN" />}><Route path="admin" element={<AdminPage />} /></Route>
        <Route path="login" element={<LoginPage />} />
        <Route path="auth/callback" element={<CallbackPage />} />
        <Route path="logout" element={<LogoutPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
