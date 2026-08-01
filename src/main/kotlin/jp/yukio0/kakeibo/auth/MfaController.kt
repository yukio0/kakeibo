package jp.yukio0.kakeibo.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.BadRequestException
import jp.yukio0.kakeibo.api.UnauthorizedException
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceService
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/mfa")
class MfaController(
  private val appUserRepository: AppUserRepository,
  private val totpService: TotpService,
  private val qrCodeService: QrCodeService,
  private val userDetailsService: UserDetailsService,
  private val securityContextRepository:
    org.springframework.security.web.context.SecurityContextRepository,
  private val trustedDeviceService: TrustedDeviceService,
  private val securityFeatureProperties: SecurityFeatureProperties,
  private val authThrottleService: AuthThrottleService,
  private val mfaRecoveryCodeService: MfaRecoveryCodeService,
) {

  @GetMapping("/status")
  @Transactional(readOnly = true)
  fun status(authentication: Authentication?): MfaStatusResponse {
    if (!securityFeatureProperties.twoFactorEnabled) {
      return MfaStatusResponse(enabled = false)
    }

    val appUser = authentication.toAppUser()
    return MfaStatusResponse(enabled = appUser.twoFactorEnabled)
  }

  @GetMapping("/setup")
  @Transactional(readOnly = true)
  fun setup(
    authentication: Authentication?,
    httpRequest: HttpServletRequest,
  ): MfaSetupResponse {
    ensureTwoFactorEnabled()

    val appUser = authentication.toAppUser()
    if (appUser.twoFactorEnabled) {
      throw BadRequestException("2段階認証はすでに有効です")
    }

    val secret = totpService.generateSecret()
    httpRequest.session.setAttribute(MfaSessionAttributes.PENDING_SETUP_SECRET, secret)
    val otpAuthUri = totpService.generateOtpAuthUri(appUser.username, secret)

    return MfaSetupResponse(
      secret = secret,
      otpauthUri = otpAuthUri,
      qrCodeSvg = qrCodeService.toSvg(otpAuthUri),
    )
  }

  /** 有効化と同時にリカバリーコードを発行する。平文を返すのはこのレスポンスと再発行APIだけ。 */
  @PostMapping("/enable")
  @Transactional
  fun enable(
    authentication: Authentication?,
    httpRequest: HttpServletRequest,
    @Valid @RequestBody request: MfaCodeRequest,
  ): MfaRecoveryCodesResponse {
    ensureTwoFactorEnabled()

    val appUser = authentication.toAppUser()
    if (appUser.twoFactorEnabled) {
      throw BadRequestException("2段階認証はすでに有効です")
    }

    val secret =
      httpRequest.session.getAttribute(MfaSessionAttributes.PENDING_SETUP_SECRET) as? String
        ?: throw BadRequestException("2段階認証の設定を開始してください")
    val code = request.code ?: ""

    if (!totpService.isValidCode(secret, code)) {
      throw ApiValidationException(
        message = "入力内容に誤りがあります",
        errors =
          listOf(
            ApiFieldErrorResponse(
              field = "code",
              message = "確認コードが正しくありません",
            )
          ),
      )
    }

    appUser.twoFactorEnabled = true
    appUser.twoFactorSecret = secret
    appUserRepository.save(appUser)
    httpRequest.session.removeAttribute(MfaSessionAttributes.PENDING_SETUP_SECRET)

    return MfaRecoveryCodesResponse(recoveryCodes = mfaRecoveryCodeService.issue(appUser))
  }

  @GetMapping("/recovery-codes")
  @Transactional(readOnly = true)
  fun recoveryCodeStatus(authentication: Authentication?): MfaRecoveryCodeStatusResponse {
    if (!securityFeatureProperties.twoFactorEnabled) {
      return MfaRecoveryCodeStatusResponse(total = 0, remaining = 0)
    }

    return mfaRecoveryCodeService.status(authentication.toAppUser())
  }

  /** 残数が減ったときや控えを失くしたときに、古いコードを破棄して発行し直す。 */
  @PostMapping("/recovery-codes")
  @Transactional
  fun regenerateRecoveryCodes(authentication: Authentication?): MfaRecoveryCodesResponse {
    ensureTwoFactorEnabled()

    val appUser = authentication.toAppUser()
    if (!appUser.twoFactorEnabled) {
      throw BadRequestException("2段階認証を有効にしてください")
    }

    return MfaRecoveryCodesResponse(recoveryCodes = mfaRecoveryCodeService.issue(appUser))
  }

  /** TOTPコードに加えて、スマホ紛失時の救済としてリカバリーコードでも通す。 */
  @PostMapping("/verify")
  @Transactional
  fun verify(
    httpRequest: HttpServletRequest,
    httpResponse: HttpServletResponse,
    @Valid @RequestBody request: MfaVerifyRequest,
  ): MfaVerifyResponse {
    ensureTwoFactorEnabled()

    val session = httpRequest.getSession(false) ?: throw UnauthorizedException("2段階認証が必要です")
    val username =
      session.getAttribute(MfaSessionAttributes.PENDING_LOGIN_USERNAME) as? String
        ?: throw UnauthorizedException("2段階認証が必要です")
    val throttleKey = AuthThrottleService.key(httpRequest, scope = "mfa", subject = username)
    authThrottleService.checkAllowed(throttleKey)

    val appUser =
      appUserRepository.findByUsername(username) ?: throw UnauthorizedException("認証ユーザーが見つかりません")
    val secret = appUser.twoFactorSecret ?: throw UnauthorizedException("2段階認証が必要です")
    val code = request.code ?: ""

    // TOTPコードの形をしていない入力だけリカバリーコードとして照合する
    val totpAccepted = appUser.twoFactorEnabled && totpService.isValidCode(secret, code)
    val recoveryCodeUsed =
      !totpAccepted &&
        appUser.twoFactorEnabled &&
        mfaRecoveryCodeService.looksLikeRecoveryCode(code) &&
        mfaRecoveryCodeService.consume(appUser, code)

    if (!totpAccepted && !recoveryCodeUsed) {
      authThrottleService.recordFailure(throttleKey)
      // ロックに達したら、この pending セッションを無効化してパスワード段からやり直させる
      runCatching { authThrottleService.checkAllowed(throttleKey) }
        .onFailure { throttled ->
          session.invalidate()
          throw throttled
        }
      throw ApiValidationException(
        message = "入力内容に誤りがあります",
        errors =
          listOf(
            ApiFieldErrorResponse(
              field = "code",
              message = "確認コードまたはリカバリーコードが正しくありません",
            )
          ),
      )
    }
    authThrottleService.reset(throttleKey)

    if (!session.isNew) {
      httpRequest.changeSessionId()
    }

    val userDetails = userDetailsService.loadUserByUsername(username)
    val authentication =
      UsernamePasswordAuthenticationToken(userDetails, null, userDetails.authorities)
    val securityContext = SecurityContextHolder.createEmptyContext()
    securityContext.authentication = authentication
    SecurityContextHolder.setContext(securityContext)
    securityContextRepository.saveContext(securityContext, httpRequest, httpResponse)
    httpRequest.session.removeAttribute(MfaSessionAttributes.PENDING_LOGIN_USERNAME)

    // リカバリーコードで通した端末は「認証アプリを持っていない端末」なので信頼済みにしない
    if (request.trustDevice == true && !recoveryCodeUsed) {
      trustedDeviceService.trustCurrentDevice(appUser, httpRequest, httpResponse)
    }

    return MfaVerifyResponse(
      username = appUser.username,
      twoFactorEnabled = appUser.twoFactorEnabled,
      recoveryCodeUsed = recoveryCodeUsed,
      remainingRecoveryCodes = mfaRecoveryCodeService.remaining(appUser),
    )
  }

  @PostMapping("/disable")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  fun disable(
    authentication: Authentication?,
    httpRequest: HttpServletRequest,
    httpResponse: HttpServletResponse,
  ) {
    if (!securityFeatureProperties.twoFactorEnabled) {
      return
    }

    val appUser = authentication.toAppUser()
    appUser.twoFactorEnabled = false
    appUser.twoFactorSecret = null
    appUserRepository.save(appUser)
    // 2FAを外したらリカバリーコードも意味を持たないので破棄する
    mfaRecoveryCodeService.deleteAll(appUser)
    httpRequest.session.removeAttribute(MfaSessionAttributes.PENDING_SETUP_SECRET)
    httpRequest.session.removeAttribute(MfaSessionAttributes.PENDING_LOGIN_USERNAME)
    trustedDeviceService.revokeAllTrustedDevices(appUser, httpResponse)
  }

  private fun ensureTwoFactorEnabled() {
    if (!securityFeatureProperties.twoFactorEnabled) {
      throw BadRequestException("2段階認証が無効です")
    }
  }

  private fun Authentication?.toAppUser(): AppUserEntity {
    val username = this?.name ?: throw UnauthorizedException("認証が必要です")
    return appUserRepository.findByUsername(username)
      ?: throw UnauthorizedException("認証ユーザーが見つかりません")
  }
}
