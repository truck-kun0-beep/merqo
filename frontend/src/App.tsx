import { Route, Routes } from 'react-router-dom'
import AppShell from './components/AppShell'
import HomePage from './pages/HomePage'
import NotFoundPage from './pages/NotFoundPage'

/**
 * The route table of the Merqo web client.
 *
 * Every business screen is added by the prompt that needs it. For now there is
 * the health home page and a not found page.
 *
 * @returns the application element
 */
export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<HomePage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
