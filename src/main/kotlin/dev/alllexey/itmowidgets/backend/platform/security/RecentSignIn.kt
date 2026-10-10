package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.platform.error.RecentSignInRequiredException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.UserDetails
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A caller authenticated by an ITMO.ID bearer token, with the token's `auth_time` when it carries one. */
class BearerAuthentication(principal: UserDetails, val authTime: Instant?) :
    UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

/**
 * Requires the caller to have signed in within [maxAge]: the bearer's ITMO.ID `auth_time` or the creation of the web
 * session (the phone-approved login). A caller without either, or with an older one, gets 403 `recent_sign_in_required`.
 */
fun Authentication.requireSignedInWithin(maxAge: Duration, clock: Clock) {
    val signedInAt = when (this) {
        is BearerAuthentication -> authTime
        is WebSessionAuthentication -> signedInAt
        else -> null
    }
    if (signedInAt == null || signedInAt.isBefore(clock.instant().minus(maxAge))) {
        throw RecentSignInRequiredException("Sign in again right before this action")
    }
}
