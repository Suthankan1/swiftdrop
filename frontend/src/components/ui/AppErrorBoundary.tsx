import { Component } from 'react'
import type { ReactNode } from 'react'

export class AppErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() { return { failed: true } }

  render() {
    if (this.state.failed) {
      return (
        <main className="page-container" id="main-content">
          <section className="state-panel" role="alert">
            <h1>We couldn’t display this page</h1>
            <p>Please return home and try again.</p>
            <a href="/" className="button button-primary">Return home</a>
          </section>
        </main>
      )
    }
    return this.props.children
  }
}
