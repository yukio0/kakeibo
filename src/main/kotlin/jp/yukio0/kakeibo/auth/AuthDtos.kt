package jp.yukio0.kakeibo.auth

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
  @field:NotBlank(message = "ユーザー名を入力してください") val username: String?,
  @field:NotBlank(message = "パスワードを入力してください") val password: String?,
)

data class ChangePasswordRequest(
  @field:NotBlank(message = "現在のパスワードを入力してください") val currentPassword: String?,
  @field:NotBlank(message = "新しいパスワードを入力してください") val newPassword: String?,
  @field:NotBlank(message = "確認用パスワードを入力してください") val newPasswordConfirm: String?,
)

data class LoginResponse(
  val mfaRequired: Boolean,
  val user: AuthUserResponse?,
)

data class SecuritySettingsResponse(
  val authenticationEnabled: Boolean,
  val twoFactorEnabled: Boolean,
)

data class AuthUserResponse(
  val username: String,
  val twoFactorEnabled: Boolean,
)

data class CsrfResponse(
  val headerName: String,
  val parameterName: String,
  val token: String,
)
