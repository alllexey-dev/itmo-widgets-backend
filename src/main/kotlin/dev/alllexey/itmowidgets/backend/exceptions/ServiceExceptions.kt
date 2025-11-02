package dev.alllexey.itmowidgets.backend.exceptions

open class ServiceException(message: String) : RuntimeException(message)

class NotFoundException(message: String) : ServiceException(message)

class PermissionDeniedException(message: String) : ServiceException(message)

class BusinessRuleException(message: String) : ServiceException(message)

class InvalidRequestDataException(message: String) : ServiceException(message)