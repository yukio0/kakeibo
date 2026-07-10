package jp.yukio0.kakeibo.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class MfaStatusResponse(val enabled: Boolean)

data class MfaSetupResponse(
  val secret: String,
  val otpauthUri: String,
  val qrCodeSvg: String,
)

data class MfaCodeRequest(
  @field:NotBlank(message = "6桁の確認コードを入力してください")
  @field:Pattern(regexp = "\\d{6}", message = "確認コードは6桁の数字で入力してください")
  val code: String?
)

data class MfaVerifyRequest(
  @field:NotBlank(message = "6桁の確認コードを入力してください")
  @field:Pattern(regexp = "\\d{6}", message = "確認コードは6桁の数字で入力してください")
  val code: String?,
  val trustDevice: Boolean? = false,
)
