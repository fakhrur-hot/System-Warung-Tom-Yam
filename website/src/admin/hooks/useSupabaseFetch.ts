import { useCallback, useEffect, useState } from 'react'
import { getSupabase } from '../../lib/supabase'

type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE' | 'PUT'

interface FetchState<T> {
  data: T | null
  loading: boolean
  error: string | null
  refetch: () => void
}

export function useSupabaseFetch<T>(functionName: string, options?: { method?: HttpMethod; body?: Record<string, unknown>; skip?: boolean }): FetchState<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(!options?.skip)
  const [error, setError] = useState<string | null>(null)

  const fetchData = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const supabase = getSupabase()
      const { data: result, error: fetchError } = await supabase.functions.invoke(functionName, {
        method: options?.method ?? 'GET' as HttpMethod,
        body: options?.body,
      })
      if (fetchError) {
        setError(fetchError.message || 'Request failed')
      } else {
        setData(result as T)
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }, [functionName, options?.method, options?.body, options?.skip])

  useEffect(() => {
    if (!options?.skip) {
      fetchData()
    }
  }, [fetchData, options?.skip])

  return { data, loading, error, refetch: fetchData }
}
