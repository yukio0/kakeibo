package jp.yukio0.kakeibo.auth

import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.UnauthorizedException
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceService
import jp.yukio0.kakeibo.user.AppUserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountSecurityService(
  private val appUserRepository: AppUserRepository,
  private val passwordEncoder: PasswordEncoder,
  private val trustedDeviceService: TrustedDeviceService,
) {

  /** パスワード更新と信頼済み端末のDB失効を、同じトランザクションで完了させる。 */
  @Transactional
  fun changePassword(username: String, request: ChangePasswordRequest) {
    val appUser =
      appUserRepository.findByUsername(username) ?: throw UnauthorizedException("認証ユーザーが見つかりません")
    val currentPassword = request.currentPassword ?: ""
    val newPassword = request.newPassword ?: ""
    val newPasswordConfirm = request.newPasswordConfirm ?: ""
    val errors = mutableListOf<ApiFieldErrorResponse>()

    if (!passwordEncoder.matches(currentPassword, appUser.passwordHash)) {
      errors.add(
        ApiFieldErrorResponse(
          field = "currentPassword",
          message = "現在のパスワードが正しくありません",
        )
      )
    }

    if (newPassword != newPasswordConfirm) {
      errors.add(
        ApiFieldErrorResponse(
          field = "newPasswordConfirm",
          message = "新しいパスワードと確認用パスワードが一致しません",
        )
      )
    }

    if (errors.isNotEmpty()) {
      throw ApiValidationException(
        message = "入力内容に誤りがあります",
        errors = errors,
      )
    }

    appUser.passwordHash = passwordEncoder.encode(newPassword) ?: error("Password hash is empty")
    appUserRepository.save(appUser)
    trustedDeviceService.revokeAllTrustedDevices(appUser)
  }
}
