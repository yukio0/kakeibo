package jp.yukio0.kakeibo.trusteddevice

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.yukio0.kakeibo.api.UnauthorizedException
import jp.yukio0.kakeibo.auth.SecurityFeatureProperties
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trusted-devices")
class TrustedDeviceController(
  private val trustedDeviceService: TrustedDeviceService,
  private val appUserRepository: AppUserRepository,
  private val securityFeatureProperties: SecurityFeatureProperties,
) {

  @GetMapping
  fun list(
    authentication: Authentication?,
    request: HttpServletRequest,
  ): List<TrustedDeviceResponse> =
    if (securityFeatureProperties.twoFactorEnabled) {
      trustedDeviceService.listTrustedDevices(authentication.toAppUser(), request)
    } else {
      emptyList()
    }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun revoke(
    authentication: Authentication?,
    @PathVariable id: Long,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ) {
    if (!securityFeatureProperties.twoFactorEnabled) {
      return
    }

    trustedDeviceService.revokeTrustedDevice(authentication.toAppUser(), id, request, response)
  }

  @DeleteMapping("/current")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun revokeCurrent(
    authentication: Authentication?,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ) {
    if (!securityFeatureProperties.twoFactorEnabled) {
      return
    }

    trustedDeviceService.revokeCurrentTrustedDevice(authentication.toAppUser(), request, response)
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun revokeAll(
    authentication: Authentication?,
    response: HttpServletResponse,
  ) {
    if (!securityFeatureProperties.twoFactorEnabled) {
      return
    }

    trustedDeviceService.revokeAllTrustedDevices(authentication.toAppUser(), response)
  }

  private fun Authentication?.toAppUser(): AppUserEntity {
    val username = this?.name ?: throw UnauthorizedException("認証が必要です")
    return appUserRepository.findByUsername(username)
      ?: throw UnauthorizedException("認証ユーザーが見つかりません")
  }
}
