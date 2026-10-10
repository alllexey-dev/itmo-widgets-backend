package dev.alllexey.itmowidgets.backend.platform.error

import com.fasterxml.jackson.annotation.JsonValue

/**
 * Every `error.code` Backend answers with. The [wire] strings are part of the contract
 * (docs/contracts/compatibility.md#error-codes): never rename one, and treat a new one as a contract change.
 */
enum class ErrorCode(@get:JsonValue val wire: String) {
    /** 400: the body, a path or query parameter could not be read. */
    INVALID_REQUEST("invalid_request"),

    /** 400: the request was read, but a value breaks a documented rule. */
    INVALID_REQUEST_DATA("invalid_request_data"),

    /** 400: bean validation rejected a request field. */
    VALIDATION_ERROR("validation_error"),

    /** 401: missing or invalid credentials. */
    UNAUTHORIZED("unauthorized"),

    /** 401: a valid web session too old for admin and moderation routes; signing in again fixes it. */
    REAUTH_REQUIRED("reauth_required"),

    /** 403: the target's owner or a role does not allow the caller. */
    PERMISSION_DENIED("permission_denied"),

    /** 403: Spring Security denied the call. */
    ACCESS_DENIED("access_denied"),

    /** 403: a moderation restriction blocks the action. */
    RESTRICTED("restricted"),

    /** 403: a web-session request other than GET or HEAD without `X-Web-Request: 1`. */
    CSRF("csrf"),

    /** 403: the action needs a sign-in more recent than the caller's; signing in again fixes it. */
    RECENT_SIGN_IN_REQUIRED("recent_sign_in_required"),

    /** 404. */
    NOT_FOUND("not_found"),

    /** 409: the request conflicts with a business rule. */
    BUSINESS_RULE_VIOLATION("business_rule_violation"),

    /** 409: the resource already exists. */
    CONFLICT("conflict"),

    /** 429. */
    RATE_LIMITED("rate_limited"),

    /** 500: details stay in the server log. */
    INTERNAL_SERVER_ERROR("internal_server_error"),
}
