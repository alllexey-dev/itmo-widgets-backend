package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential

/** An admin replaced the value; listeners receive it after the replacement commits. */
data class ServiceCredentialReplaced(val credential: ServiceCredential)
