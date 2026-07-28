import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { getSupabase } from '../../lib/supabase'

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [success, setSuccess] = useState(false)
  const [attemptCount, setAttemptCount] = useState(0)

  const tooManyAttempts = attemptCount >= 3

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    if (tooManyAttempts) return
    setAttemptCount((c) => c + 1)
    setLoading(true)

    try {
      const supabase = getSupabase()
      const { error: resetError } = await supabase.auth.resetPasswordForEmail(email)

      if (resetError) {
        setError(resetError.message)
      } else {
        setSuccess(true)
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Request failed.')
    } finally {
      setLoading(false)
    }
  }

  if (success) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
        <div className="w-full max-w-md rounded-lg bg-white p-8 shadow-md">
          <div className="text-center">
            <span className="mb-4 block text-5xl" aria-hidden="true">📧</span>
            <h1 className="text-xl font-bold text-emerald-800">Recovery Email Sent</h1>
            <p className="mt-3 text-sm text-gray-600">
              If an account exists for <strong>{email}</strong>, you'll receive a password reset link shortly.
            </p>
            <Link
              to="/admin/login"
              className="mt-6 inline-block rounded-md bg-emerald-600 px-6 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
            >
              Back to Login
            </Link>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-md rounded-lg bg-white p-8 shadow-md">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold text-emerald-800">Forgot Password</h1>
          <p className="mt-1 text-sm text-gray-500">Enter your email and we'll send a recovery link.</p>
        </div>

        <form onSubmit={handleSubmit} noValidate>
          {error && (
            <div className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
              {error}
            </div>
          )}

          <div className="mb-6">
            <label htmlFor="email" className="mb-1 block text-sm font-medium text-gray-700">
              Email Address
            </label>
            <input
              id="email"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
              placeholder="admin@example.com"
            />
          </div>

          <button
            type="submit"
            disabled={loading || tooManyAttempts}
            className="w-full rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? 'Sending...' : 'Send Recovery Link'}
          </button>
          {tooManyAttempts && (
            <p className="mt-3 text-center text-sm text-red-600">
              Too many reset attempts. Please wait before trying again.
            </p>
          )}
        </form>

        <p className="mt-4 text-center text-sm text-gray-500">
          Remember your password?{' '}
          <Link to="/admin/login" className="font-medium text-emerald-600 hover:text-emerald-700">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  )
}
