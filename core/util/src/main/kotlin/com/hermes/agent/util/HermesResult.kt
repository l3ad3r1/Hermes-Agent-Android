package com.hermes.agent.util

/**
 * Lightweight Result type for domain operations that need to surface
 * structured errors to the UI without throwing.
 *
 * Deliberately minimal — does not try to re-implement kotlin.Result.
 */
sealed class HermesResult<out T> {
    data class Success<T>(val value: T) : HermesResult<T>()
    data class Failure(val error: Throwable) : HermesResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): HermesResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): HermesResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (Throwable) -> Unit): HermesResult<T> {
        if (this is Failure) block(error)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.value
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}
