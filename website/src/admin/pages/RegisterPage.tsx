import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { getSupabase } from '../../lib/supabase'

const MAX_ATTEMPTS = 3

export default function RegisterPage() {
  const [inviteToken, setInviteToken] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [success, setSuccess] = useState(false)
  const [attemptCount, setAttemptCount] = useState(0)

  const tooManyAttempts = attemptCount >= MAX_ATTEMPTS

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)

    if (tooManyAttempts) return

    if (!inviteToken.trim()) {
      setError('An invite token is required.')
      setAttemptCount((c) => c + 1)
      return
    }

    if (password !== confirmPassword) {
      setError('Passwords do not match.')
      setAttemptCount((c) => c + 1)
      return
    }

    if (password.length < 6) {
      setError('Password must be at least 6 characters.')
      setAttemptCount((c) => c + 1)
      return
    }

    setAttemptCount((c) => c + 1)
    setLoading(true)
    try {
      const supabase = getSupabase()
      const { error: signUpError } = await supabase.auth.signUp({
        email,
        password,
        options: {
          data: { invite_token: inviteToken },
        },
      })

      if (signUpError) {
        setError(signUpError.message)
      } else {
        setSuccess(true)
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Registration failed.')
    } finally {
      setLoading(false)
    }
  }

  if (success) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
        <div className="w-full max-w-md rounded-lg bg-white p-8 shadow-md">
          <div className="text-center">
            <span className="mb-4 block text-5xl" aria-hidden="true">✉️</span>
            <h1 className="text-xl font-bold text-emerald-800">Check Your Email</h1>
            <p className="mt-3 text-sm text-gray-600">
              We've sent a verification link to <strong>{email}</strong>. 
              Click the link to activate your account, then sign in.
            </p>
            <Link
              to="/admin/login"
              className="mt-6 inline-block rounded-md bg-emerald-600 px-6 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
            >
              Go to Login
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
          <h1 className="text-2xl font-bold text-emerald-800">Create Admin Account</h1>
          <p className="mt-1 text-sm text-gray-500">Registration requires an invite token from your Supabase administrator.</p>
        </div>

        <form onSubmit={handleSubmit} noValidate>
          {tooManyAttempts && (
            <div className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
              Too many registration attempts. Please contact your administrator.
            </div>
          )}
          {error && !tooManyAttempts && (
            <div className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
              {error}
            </div>
          )}

          <div className="mb-4">
            <label htmlFor="inviteToken" className="mb-1 block text-sm font-medium text-gray-700">
              Admin Invite Token
            </label>
            <input
              id="inviteToken"
              type="text"
              required
              autoComplete="off"
              value={inviteToken}
              onChange={(e) => setInviteToken(e.target.value)}
              disabled={tooManyAttempts}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
              placeholder="Enter your invite token"
            />
          </div>

          <div className="mb-4">
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
              disabled={tooManyAttempts}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
              placeholder="admin@example.com"
            />
          </div>

          <div className="mb-4">
            <label htmlFor="password" className="mb-1 block text-sm font-medium text-gray-700">
              Password
            </label>
            <input
              id="password"
              type="password"
              required
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={tooManyAttempts}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
              placeholder="At least 6 characters"
            />
          </div>

          <div className="mb-6">
            <label htmlFor="confirmPassword" className="mb-1 block text-sm font-medium text-gray-700">
              Confirm Password
            </label>
            <input
              id="confirmPassword"
              type="password"
              required
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              disabled={tooManyAttempts}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
              placeholder="Re-enter your password"
            />
          </div>

          <button
            type="submit"
            disabled={loading || tooManyAttempts}
            className="w-full rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? 'Creating Account...' : 'Create Account'}
          </button>
        </form>

        <p className="mt-4 text-center text-sm text-gray-500">
          Already have an account?{' '}
          <Link to="/admin/login" className="font-medium text-emerald-600 hover:text-emerald-700">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  )
}
