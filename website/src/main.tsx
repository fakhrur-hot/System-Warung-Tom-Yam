import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import App from './App.tsx'
import RegisterPage from './admin/pages/RegisterPage.tsx'
import LoginPage from './admin/pages/LoginPage.tsx'
import ForgotPasswordPage from './admin/pages/ForgotPasswordPage.tsx'
import DashboardPage from './admin/pages/DashboardPage.tsx'
import DevicesPage from './admin/pages/DevicesPage.tsx'
import OrdersPage from './admin/pages/OrdersPage.tsx'
import SettingsPage from './admin/pages/SettingsPage.tsx'
import QrSheetsPage from './admin/pages/QrSheetsPage.tsx'
import AdminLayout from './admin/components/AdminLayout.tsx'
import ProtectedRoute from './admin/components/ProtectedRoute.tsx'
import ErrorBoundary from './components/ErrorBoundary.tsx'
import './i18n.ts'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
      <Routes>
        {/* Customer ordering page */}
        <Route path="/order" element={<App />} />

        {/* Admin auth pages (public) */}
        <Route path="/admin/register" element={<RegisterPage />} />
        <Route path="/admin/login" element={<LoginPage />} />
        <Route path="/admin/forgot-password" element={<ForgotPasswordPage />} />

        {/* Admin protected pages */}
        <Route
          path="/admin"
          element={
            <ProtectedRoute>
              <AdminLayout />
            </ProtectedRoute>
          }
        >
          <Route path="dashboard" element={<DashboardPage />} />
          <Route path="devices" element={<DevicesPage />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="qr-sheets" element={<QrSheetsPage />} />
          {/* Default redirect for /admin */}
          <Route index element={<DashboardPage />} />
        </Route>

        {/* Fallback — treat root as customer ordering page */}
        <Route path="*" element={<App />} />
      </Routes>
    </BrowserRouter>
    </ErrorBoundary>
  </React.StrictMode>,
)
