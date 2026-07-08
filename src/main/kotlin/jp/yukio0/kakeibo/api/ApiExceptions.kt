package jp.yukio0.kakeibo.api

class BadRequestException(message: String) : RuntimeException(message)

class ResourceNotFoundException(message: String) : RuntimeException(message)
