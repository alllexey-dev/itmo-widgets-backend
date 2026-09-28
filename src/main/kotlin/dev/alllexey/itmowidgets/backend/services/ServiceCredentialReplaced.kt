package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.ServiceCredential

/** An admin replaced the value; listeners receive it after the replacement commits. */
data class ServiceCredentialReplaced(val credential: ServiceCredential)
