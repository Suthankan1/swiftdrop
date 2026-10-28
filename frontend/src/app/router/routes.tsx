import { Route, Routes } from 'react-router'
import { AppLayout } from '../../components/layout/AppLayout'
import { AdminPage } from '../../features/admin/pages/AdminPage'
import { PricingAdminPage } from '../../features/admin/pages/PricingAdminPage'
import { UserStaffPage } from '../../features/admin/pages/UserStaffPage'
import { MerchantReviewPage } from '../../features/admin/pages/MerchantReviewPage'
import { ReportsPage } from '../../features/admin/pages/ReportsPage'
import { CourierPage } from '../../features/courier/pages/CourierPage'
import { CustomerPage } from '../../features/customer/pages/CustomerPage'
import { CustomerQuotePage } from '../../features/customer/pages/CustomerQuotePage'
import { CustomerBookingConfirmedPage } from '../../features/customer/pages/CustomerBookingConfirmedPage'
import { CustomerHistoryPage } from '../../features/customer/pages/CustomerHistoryPage'
import { DispatcherPage } from '../../features/dispatcher/pages/DispatcherPage'
import { MerchantPage } from '../../features/merchant/pages/MerchantPage'
import { MerchantOrdersDashboardPage } from '../../features/merchant/pages/MerchantOrdersDashboardPage'
import { MerchantOrderDetailPage } from '../../features/merchant/pages/MerchantOrderDetailPage'
import { MerchantWebhooksPage } from '../../features/merchant/pages/MerchantWebhooksPage'
import { MerchantEnrollmentPage } from '../../features/merchant/pages/MerchantEnrollmentPage'
import { MerchantCredentialsPage } from '../../features/merchant/pages/MerchantCredentialsPage'
import { LandingPage } from '../../features/public/LandingPage'
import { PublicTrackingPage } from '../../features/public/PublicTrackingPage'
import { NotFoundPage } from '../../features/public/NotFoundPage'
import { LoginPage, CallbackPage, LogoutPage, ProtectedRoute } from '../../auth/AuthPages'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<LandingPage />} />
        <Route path="tracking" element={<PublicTrackingPage />} />
        <Route path="tracking/:trackingNumber" element={<PublicTrackingPage />} />
        <Route element={<ProtectedRoute role="CUSTOMER" />}>
          <Route path="customer" element={<CustomerPage />} />
          <Route path="customer/quote" element={<CustomerQuotePage />} />
          <Route path="customer/booking/confirmed/:parcelId" element={<CustomerBookingConfirmedPage />} />
          <Route path="customer/history" element={<CustomerHistoryPage />} />
        </Route>
        <Route element={<ProtectedRoute />}>
          <Route path="merchant/enroll" element={<MerchantEnrollmentPage />} />
        </Route>
        <Route element={<ProtectedRoute role="MERCHANT" />}>
          <Route path="merchant" element={<MerchantPage />} />
          <Route path="merchant/orders" element={<MerchantOrdersDashboardPage />} />
          <Route path="merchant/orders/:parcelId" element={<MerchantOrderDetailPage />} />
          <Route path="merchant/credentials" element={<MerchantCredentialsPage />} />
          <Route path="merchant/webhooks" element={<MerchantWebhooksPage />} />
        </Route>
        <Route element={<ProtectedRoute role="COURIER" />}><Route path="courier" element={<CourierPage />} /></Route>
        <Route element={<ProtectedRoute role="DISPATCHER" />}>
          <Route path="dispatcher" element={<DispatcherPage />} />
          <Route path="dispatcher/reports" element={<ReportsPage audience="DISPATCHER" />} />
        </Route>
        <Route element={<ProtectedRoute role="ADMIN" />}>
          <Route path="admin" element={<AdminPage />} />
          <Route path="admin/users" element={<UserStaffPage section="users" />} />
          <Route path="admin/staff" element={<UserStaffPage section="staff" />} />
          <Route path="admin/staff/:profileId" element={<UserStaffPage section="detail" />} />
          <Route path="admin/merchants" element={<MerchantReviewPage />} />
          <Route path="admin/pricing" element={<PricingAdminPage />} />
          <Route path="admin/reports" element={<ReportsPage audience="ADMIN" />} />
        </Route>
        <Route path="login" element={<LoginPage />} />
        <Route path="auth/callback" element={<CallbackPage />} />
        <Route path="logout" element={<LogoutPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
