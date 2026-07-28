import { Component } from 'react';
import { AlertTriangle, RotateCcw, Home } from 'lucide-react';

/**
 * Catches render-time errors so one broken component shows a recoverable panel
 * instead of blanking the whole app.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // Keep the stack in the console for debugging; there is no error-reporting
    // backend in this project.
    console.error('Unhandled UI error:', error, info?.componentStack);
  }

  handleReset = () => this.setState({ error: null });

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div className="min-h-screen flex items-center justify-center px-4 py-16">
        <div className="card p-8 max-w-lg w-full text-center">
          <span className="grid place-items-center w-14 h-14 rounded-2xl bg-red-50 text-red-500 mx-auto mb-5">
            <AlertTriangle size={26} />
          </span>
          <h1 className="font-display text-xl font-bold text-ink-900 mb-2">Something went wrong</h1>
          <p className="text-sm text-ink-500 mb-6">
            This part of the page failed to load. Try again, or head back to the homepage.
          </p>

          {import.meta.env.DEV && (
            <pre className="surface-muted p-3 mb-6 text-left text-xs text-red-700 font-mono whitespace-pre-wrap max-h-40 overflow-y-auto">
              {String(error?.message ?? error)}
            </pre>
          )}

          <div className="flex flex-wrap gap-3 justify-center">
            <button type="button" onClick={this.handleReset} className="btn-primary">
              <RotateCcw size={16} /> Try again
            </button>
            <a href={import.meta.env.BASE_URL || '/'} className="btn-secondary">
              <Home size={16} /> Go home
            </a>
          </div>
        </div>
      </div>
    );
  }
}
