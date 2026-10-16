import { Route, Routes } from 'react-router'
import { AppLayout } from '../../components/layout/AppLayout'
import { CustomerPage } from '../../features/customer/pages/CustomerPage'
import { LandingPage } from '../../features/public/LandingPage'
import { NotFoundPage } from '../../features/public/NotFoundPage'
import { LoginPage, CallbackPage, LogoutPage, ProtectedRoute } from '../../auth/AuthPages'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<LandingPage />} />
        <Route element={<ProtectedRoute role="CUSTOMER" />}><Route path="customer" element={<CustomerPage />} /></Route>
        <Route path="login" element={<LoginPage />} />
        <Route path="auth/callback" element={<CallbackPage />} />
        <Route path="logout" element={<LogoutPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
