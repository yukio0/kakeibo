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

/** 発行直後のリカバリーコード。平文を返すのはこのレスポンスだけ。 */
data class MfaRecoveryCodesResponse(val recoveryCodes: List<String>)

data class MfaRecoveryCodeStatusResponse(val total: Int, val remaining: Int)

data class MfaVerifyRequest(
  @field:NotBlank(message = "確認コードまたはリカバリーコードを入力してください")
  @field:Pattern(
    // 6桁のTOTPコード、またはハイフンや空白を含みうるリカバリーコード
    regexp = "\\d{6}|[0-9A-Za-z][0-9A-Za-z -]{8,20}[0-9A-Za-z]",
    message = "6桁の確認コードまたはリカバリーコードを入力してください",
  )
  val code: String?,
  val trustDevice: Boolean? = false,
)

/** 2段階認証の完了結果。リカバリーコードで通した場合は再発行を促せるよう残数も返す。 */
data class MfaVerifyResponse(
  val username: String,
  val twoFactorEnabled: Boolean,
  val recoveryCodeUsed: Boolean,
  val remainingRecoveryCodes: Int,
)
