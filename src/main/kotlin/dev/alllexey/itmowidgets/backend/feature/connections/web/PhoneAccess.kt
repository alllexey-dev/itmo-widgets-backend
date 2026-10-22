package dev.alllexey.itmowidgets.backend.feature.connections.web

import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.platform.security.BearerAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.uuid
import org.springframework.security.core.Authentication
import java.util.UUID

/** The app's ITMO.ID bearer only: no connection, the web included, approves or reconciles for the phone. */
internal fun Authentication.phoneUser(): UUID =
    if (this is BearerAuthentication) uuid() else throw PermissionDeniedException("Only the app does this")
