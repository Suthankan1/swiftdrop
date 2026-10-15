import { Route, Routes } from 'react-router'
import { AppLayout } from '../../components/layout/AppLayout'
import { CustomerPage } from '../../features/customer/pages/CustomerPage'
import { LandingPage } from '../../features/public/LandingPage'
import { NotFoundPage } from '../../features/public/NotFoundPage'
import { FeedbackState } from '../../components/ui/FeedbackState'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<LandingPage />} />
        <Route path="customer" element={<CustomerPage />} />
        {['login', 'auth/callback', 'logout'].map((path) => (
          <Route key={path} path={path} element={<AuthenticationPending />} />
        ))}
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

function AuthenticationPending() {
  return <div className="page-container"><FeedbackState title="Secure sign-in is coming next">Authentication is not implemented yet. This page cannot sign you in or manage a session.</FeedbackState></div>
}
