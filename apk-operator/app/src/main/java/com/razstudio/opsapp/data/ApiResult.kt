package com.razstudio.opsapp.data

/**
 * Generic API call outcome, mirroring `apk/app`'s `com.razstudio.pos.data.ApiResult` (a small
 * slice extracted here rather than porting that file's host, `ApiClient.kt`, which is a large,
 * POS-specific HTTP client this app has no use for).
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: String, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val message: String) : ApiResult<Nothing>()
}
