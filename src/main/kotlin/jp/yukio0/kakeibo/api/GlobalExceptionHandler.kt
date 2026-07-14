package jp.yukio0.kakeibo.api

import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException::class)
  fun handleMethodArgumentNotValid(
    exception: MethodArgumentNotValidException
  ): ResponseEntity<ApiErrorResponse> {
    val errors =
      exception.bindingResult.fieldErrors.map {
        ApiFieldErrorResponse(
          field = it.field,
          message = it.defaultMessage ?: "入力値が不正です",
        )
      }
    return badRequest("入力内容に誤りがあります", errors)
  }

  @ExceptionHandler(ConstraintViolationException::class)
  fun handleConstraintViolation(
    exception: ConstraintViolationException
  ): ResponseEntity<ApiErrorResponse> {
    val errors =
      exception.constraintViolations.map {
        ApiFieldErrorResponse(
          field = it.propertyPath.toString(),
          message = it.message,
        )
      }
    return badRequest("入力内容に誤りがあります", errors)
  }

  @ExceptionHandler(
    HttpMessageNotReadableException::class,
    MethodArgumentTypeMismatchException::class,
  )
  fun handleInvalidRequestFormat(): ResponseEntity<ApiErrorResponse> = badRequest("リクエストの形式が不正です")

  @ExceptionHandler(ApiValidationException::class)
  fun handleApiValidation(exception: ApiValidationException): ResponseEntity<ApiErrorResponse> =
    badRequest(
      message = exception.message ?: "入力内容に誤りがあります",
      errors = exception.errors,
    )

  @ExceptionHandler(BadRequestException::class)
  fun handleBadRequest(exception: BadRequestException): ResponseEntity<ApiErrorResponse> =
    badRequest(exception.message ?: "入力内容に誤りがあります")

  @ExceptionHandler(DataIntegrityViolationException::class)
  fun handleDataIntegrityViolation(): ResponseEntity<ApiErrorResponse> =
    badRequest("データの制約に違反しています")

  @ExceptionHandler(ResourceNotFoundException::class, NoResourceFoundException::class)
  fun handleNotFound(exception: Exception): ResponseEntity<ApiErrorResponse> =
    ResponseEntity.status(HttpStatus.NOT_FOUND)
      .body(ApiErrorResponse(message = exception.message ?: "対象データが見つかりません"))

  @ExceptionHandler(UnauthorizedException::class)
  fun handleUnauthorized(exception: UnauthorizedException): ResponseEntity<ApiErrorResponse> =
    ResponseEntity.status(HttpStatus.UNAUTHORIZED)
      .body(ApiErrorResponse(message = exception.message ?: "認証が必要です"))

  @ExceptionHandler(TooManyRequestsException::class)
  fun handleTooManyRequests(exception: TooManyRequestsException): ResponseEntity<ApiErrorResponse> =
    ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
      .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
      .body(ApiErrorResponse(message = exception.message ?: "しばらくしてからお試しください"))

  @ExceptionHandler(MaxUploadSizeExceededException::class)
  fun handleMaxUploadSize(): ResponseEntity<ApiErrorResponse> =
    ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
      .body(ApiErrorResponse(message = "ファイルサイズが大きすぎます"))

  @ExceptionHandler(Exception::class)
  fun handleUnexpectedException(exception: Exception): ResponseEntity<ApiErrorResponse> {
    logger.error("Unhandled API exception", exception)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
      .body(ApiErrorResponse(message = "サーバー内部でエラーが発生しました"))
  }

  private fun badRequest(
    message: String,
    errors: List<ApiFieldErrorResponse> = emptyList(),
  ): ResponseEntity<ApiErrorResponse> =
    ResponseEntity.status(HttpStatus.BAD_REQUEST)
      .body(ApiErrorResponse(message = message, errors = errors))

  private companion object {
    val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
  }
}
