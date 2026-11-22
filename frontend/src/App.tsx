import { Route, Routes } from 'react-router-dom'
import AppShell from './components/AppShell'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import LocationsPage from './pages/LocationsPage'
import UsersPage from './pages/UsersPage'
import UserDetailPage from './pages/UserDetailPage'
import PermissionTemplatesPage from './pages/PermissionTemplatesPage'
import AuditPage from './pages/AuditPage'
import SettingsPage from './pages/SettingsPage'
import ChangePasswordPage from './pages/ChangePasswordPage'
import NotFoundPage from './pages/NotFoundPage'
import RequireSession, { RequireSignedOut } from './session/RequireSession'

/**
 * The route table of the Merqo web client.
 *
 * Every business screen is added by the prompt that needs it. For now there is
 * the login page, the health home page, the locations, the people, the account
 * detail, the permission templates, the audit and the settings screens.
 *
 * @returns the application element
 */
export default function App() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RequireSignedOut>
            <LoginPage />
          </RequireSignedOut>
        }
      />
      <Route element={<AppShell />}>
        <Route
          index
          element={
            <RequireSession>
              <HomePage />
            </RequireSession>
          }
        />
        <Route
          path="locations"
          element={
            <RequireSession>
              <LocationsPage />
            </RequireSession>
          }
        />
        <Route
          path="users"
          element={
            <RequireSession permission="USER_VIEW">
              <UsersPage />
            </RequireSession>
          }
        />
        <Route
          path="users/:id"
          element={
            <RequireSession permission="USER_VIEW">
              <UserDetailPage />
            </RequireSession>
          }
        />
        <Route
          path="permission-templates"
          element={
            <RequireSession permission="TEMPLATE_MANAGE">
              <PermissionTemplatesPage />
            </RequireSession>
          }
        />
        <Route
          path="audit"
          element={
            <RequireSession permission="AUDIT_VIEW">
              <AuditPage />
            </RequireSession>
          }
        />
        <Route
          path="settings"
          element={
            <RequireSession permission="SETTINGS_VIEW">
              <SettingsPage />
            </RequireSession>
          }
        />
        <Route
          path="change-password"
          element={
            <RequireSession>
              <ChangePasswordPage />
            </RequireSession>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
