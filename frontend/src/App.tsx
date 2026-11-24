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
import ProductsPage from './pages/ProductsPage'
import ProductDetailPage from './pages/ProductDetailPage'
import CatalogImportPage from './pages/CatalogImportPage'
import PromotionsPage from './pages/PromotionsPage'
import InventoryPage from './pages/InventoryPage'
import StockCountsPage from './pages/StockCountsPage'
import TransfersPage from './pages/TransfersPage'
import TransferDetailPage from './pages/TransferDetailPage'
import DevReceiptPreviewPage from './pages/DevReceiptPreviewPage'
import NotFoundPage from './pages/NotFoundPage'
import RequireSession, { RequireSignedOut } from './session/RequireSession'

/**
 * The route table of the Merqo web client.
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
          path="catalog/products"
          element={
            <RequireSession permission="PRODUCT_VIEW">
              <ProductsPage />
            </RequireSession>
          }
        />
        <Route
          path="catalog/products/:id"
          element={
            <RequireSession permission="PRODUCT_VIEW">
              <ProductDetailPage />
            </RequireSession>
          }
        />
        <Route
          path="catalog/import"
          element={
            <RequireSession anyPermission={['PRODUCT_CREATE', 'PRODUCT_EDIT']}>
              <CatalogImportPage />
            </RequireSession>
          }
        />
        <Route
          path="promotions"
          element={
            <RequireSession permission="DISCOUNT_MANAGE">
              <PromotionsPage />
            </RequireSession>
          }
        />
        <Route
          path="inventory"
          element={
            <RequireSession permission="INVENTORY_VIEW">
              <InventoryPage />
            </RequireSession>
          }
        />
        <Route
          path="inventory/counts"
          element={
            <RequireSession anyPermission={['INVENTORY_VIEW', 'STOCK_COUNT']}>
              <StockCountsPage />
            </RequireSession>
          }
        />
        <Route
          path="transfers"
          element={
            <RequireSession permission="TRANSFER_VIEW">
              <TransfersPage />
            </RequireSession>
          }
        />
        <Route
          path="transfers/:id"
          element={
            <RequireSession permission="TRANSFER_VIEW">
              <TransferDetailPage />
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
        {import.meta.env.DEV ? (
          <Route path="dev/receipt-preview" element={<DevReceiptPreviewPage />} />
        ) : null}
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
