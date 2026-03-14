package com.ashotn.opencode.relay.api.transport

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

inline fun <T, R> ApiResult<T>.flatMap(transform: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
    is ApiResult.Success -> transform(value)
    is ApiResult.Failure -> this
}
