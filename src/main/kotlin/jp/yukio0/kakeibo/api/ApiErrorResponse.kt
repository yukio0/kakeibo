package jp.yukio0.kakeibo.api

data class ApiErrorResponse(
  val message: String,
  val errors: List<ApiFieldErrorResponse> = emptyList(),
)

data class ApiFieldErrorResponse(
  val field: String,
  val message: String,
)
