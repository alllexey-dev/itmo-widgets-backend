package dev.alllexey.itmowidgets.backend.platform.error

import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability

open class ServiceException(message: String) : RuntimeException(message)

class NotFoundException(message: String) : ServiceException(message)

class PermissionDeniedException(message: String) : ServiceException(message)

class BusinessRuleException(message: String) : ServiceException(message)

class InvalidRequestDataException(message: String) : ServiceException(message)

class RestrictedException(val capability: RestrictionCapability, val expiresAt: java.time.Instant?, message: String) :
    ServiceException(message)

class TooManyRequestsException(message: String) : ServiceException(message)

class RecentSignInRequiredException(message: String) : ServiceException(message)
