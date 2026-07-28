import { Component, ErrorInfo, ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

/**
 * Top-level error boundary — catches uncaught render errors and shows a
 * friendly fallback instead of a blank screen. (L-11)
 */
export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false }
  }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Uncaught render error:', error, info)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen items-center justify-center bg-emerald-50 px-4">
          <div className="text-center">
            <span className="mb-4 block text-5xl" aria-hidden="true">⚠️</span>
            <h1 className="text-lg font-semibold text-emerald-900">Something went wrong</h1>
            <p className="mt-2 text-sm text-emerald-600">Please refresh the page to try again.</p>
            <button
              onClick={() => window.location.reload()}
              className="mt-4 rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white hover:bg-emerald-700"
            >
              Refresh
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
