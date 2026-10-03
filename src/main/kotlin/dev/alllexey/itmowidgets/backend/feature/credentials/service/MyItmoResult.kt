package dev.alllexey.itmowidgets.backend.feature.credentials.service

/** The outcome of one [MyItmoGateway] call. A failure carries no response body, message or token. */
sealed interface MyItmoResult<out T> {
    data class Success<out T>(val value: T) : MyItmoResult<T>

    /**
     * [temporary] means the failure says nothing about the requested record, so the same call may succeed later.
     * [cause] is only for the stack trace of an operator log.
     */
    sealed class Failure(val kind: MyItmoFailureKind, val temporary: Boolean, val cause: Throwable?) : MyItmoResult<Nothing>

    /** MyITMO answered with a status outside 2xx; 401 and 403 reject the technical credential. */
    class HttpStatus(val status: Int) :
        Failure(
            kind = if (status == 401 || status == 403) MyItmoFailureKind.AUTH else MyItmoFailureKind.HTTP,
            temporary = status >= 500 || status == 429,
            cause = null,
        )

    /** A 2xx answer without a body, with a nonzero `error_code` or without `result`; an empty result is valid. */
    data object InvalidEnvelope : Failure(MyItmoFailureKind.HTTP, temporary = false, cause = null)

    /** The technical credential could not be refreshed. */
    class CredentialRefreshFailed(cause: Throwable) : Failure(MyItmoFailureKind.AUTH, temporary = true, cause = cause)

    /** No answer: the connection failed or timed out. */
    class TransportFailed(cause: Throwable) : Failure(MyItmoFailureKind.NETWORK, temporary = true, cause = cause)

    /** The answer could not be read as JSON of the expected shape. */
    class MalformedBody(cause: Throwable) : Failure(MyItmoFailureKind.MAPPING, temporary = true, cause = cause)
}

enum class MyItmoFailureKind { AUTH, HTTP, NETWORK, MAPPING }
