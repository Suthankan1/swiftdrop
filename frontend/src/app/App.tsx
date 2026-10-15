import { BrowserRouter } from 'react-router'
import { AppProviders } from './providers/AppProviders'
import { AppRoutes } from './router/routes'
import { AppErrorBoundary } from '../components/ui/AppErrorBoundary'

export function App() {
  return <AppErrorBoundary><AppProviders><BrowserRouter><AppRoutes /></BrowserRouter></AppProviders></AppErrorBoundary>
}
