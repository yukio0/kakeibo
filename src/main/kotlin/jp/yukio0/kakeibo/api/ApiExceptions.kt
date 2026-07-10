package jp.yukio0.kakeibo.api

class BadRequestException(message: String) : RuntimeException(message)

class ApiValidationException(
  message: String,
  val errors: List<ApiFieldErrorResponse>,
) : RuntimeException(message)

class ResourceNotFoundException(message: String) : RuntimeException(message)

class UnauthorizedException(message: String) : RuntimeException(message)
