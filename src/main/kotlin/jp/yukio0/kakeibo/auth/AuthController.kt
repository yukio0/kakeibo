package jp.yukio0.kakeibo.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jp.yukio0.kakeibo.api.BadRequestException
import jp.yukio0.kakeibo.api.UnauthorizedException
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceService
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class AuthController(
  private val authenticationManager: AuthenticationManager,
  private val appUserRepository: AppUserRepository,
  private val accountSecurityService: AccountSecurityService,
  private val securityContextRepository: SecurityContextRepository,
  private val trustedDeviceService: TrustedDeviceService,
  private val securityFeatureProperties: SecurityFeatureProperties,
  private val authThrottleService: AuthThrottleService,
) {

  @PostMapping("/login")
  fun login(
    @Valid @RequestBody request: LoginRequest,
    httpRequest: HttpServletRequest,
    httpResponse: HttpServletResponse,
  ): LoginResponse {
    if (!securityFeatureProperties.authenticationEnabled) {
      return LoginResponse(
        mfaRequired = false,
        user = disabledAuthenticationUser(),
      )
    }

    val username = request.username.orEmpty().trim()
    val throttleKey = AuthThrottleService.key(httpRequest, scope = "login", subject = username)
    authThrottleService.checkAllowed(throttleKey)

    val authentication =
      try {
        authenticationManager.authenticate(
          UsernamePasswordAuthenticationToken(username, request.password)
        )
      } catch (exception: AuthenticationException) {
        authThrottleService.recordFailure(throttleKey)
        throw UnauthorizedException("ユーザー名またはパスワードが正しくありません")
      }
    authThrottleService.reset(throttleKey)

    val appUser = authentication.toAppUser()
    val session = httpRequest.getSession(true)
    if (!session.isNew) {
      httpRequest.changeSessionId()
    }

    session.removeAttribute(MfaSessionAttributes.PENDING_SETUP_SECRET)
    session.removeAttribute(MfaSessionAttributes.PENDING_LOGIN_USERNAME)

    if (
      securityFeatureProperties.twoFactorEnabled &&
        appUser.twoFactorEnabled &&
        !appUser.twoFactorSecret.isNullOrBlank()
    ) {
      if (trustedDeviceService.isTrustedDevice(appUser, httpRequest, httpResponse)) {
        saveAuthentication(authentication, httpRequest, httpResponse)
        return LoginResponse(
          mfaRequired = false,
          user = appUser.toResponse(),
        )
      }

      session.setAttribute(MfaSessionAttributes.PENDING_LOGIN_USERNAME, appUser.username)
      SecurityContextHolder.clearContext()
      return LoginResponse(
        mfaRequired = true,
        user = null,
      )
    }

    saveAuthentication(authentication, httpRequest, httpResponse)

    return LoginResponse(
      mfaRequired = false,
      user = appUser.toResponse(),
    )
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun logout(httpRequest: HttpServletRequest, httpResponse: HttpServletResponse) {
    if (!securityFeatureProperties.authenticationEnabled) {
      return
    }

    SecurityContextLogoutHandler()
      .logout(httpRequest, httpResponse, SecurityContextHolder.getContext().authentication)
  }

  @GetMapping("/me")
  fun me(authentication: Authentication?): AuthUserResponse =
    if (securityFeatureProperties.authenticationEnabled) {
      authentication?.toResponse() ?: throw UnauthorizedException("認証が必要です")
    } else {
      disabledAuthenticationUser()
    }

  @PutMapping("/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun changePassword(
    authentication: Authentication?,
    httpResponse: HttpServletResponse,
    @Valid @RequestBody request: ChangePasswordRequest,
  ) {
    if (!securityFeatureProperties.authenticationEnabled) {
      throw BadRequestException("ログイン認証が無効です")
    }

    val username = authentication?.name ?: throw UnauthorizedException("認証が必要です")
    accountSecurityService.changePassword(username, request)
    // DBトランザクションが正常にコミットされた後で、ブラウザ側のCookieを削除する。
    trustedDeviceService.clearTrustedDeviceCookie(httpResponse)
  }

  @GetMapping("/csrf")
  fun csrf(csrfToken: CsrfToken): CsrfResponse =
    CsrfResponse(
      headerName = csrfToken.headerName,
      parameterName = csrfToken.parameterName,
      token = csrfToken.token,
    )

  @GetMapping("/security-settings")
  fun securitySettings(): SecuritySettingsResponse =
    SecuritySettingsResponse(
      authenticationEnabled = securityFeatureProperties.authenticationEnabled,
      twoFactorEnabled = securityFeatureProperties.twoFactorEnabled,
    )

  private fun Authentication.toResponse(): AuthUserResponse {
    val appUser = toAppUser()
    return appUser.toResponse()
  }

  private fun AppUserEntity.toResponse(): AuthUserResponse =
    AuthUserResponse(
      username = username,
      twoFactorEnabled = securityFeatureProperties.twoFactorEnabled && twoFactorEnabled,
    )

  private fun disabledAuthenticationUser(): AuthUserResponse =
    AuthUserResponse(
      username = "認証無効",
      twoFactorEnabled = false,
    )

  private fun saveAuthentication(
    authentication: Authentication,
    httpRequest: HttpServletRequest,
    httpResponse: HttpServletResponse,
  ) {
    val securityContext = SecurityContextHolder.createEmptyContext()
    securityContext.authentication = authentication
    SecurityContextHolder.setContext(securityContext)
    securityContextRepository.saveContext(securityContext, httpRequest, httpResponse)
  }

  private fun Authentication.toAppUser(): AppUserEntity =
    appUserRepository.findByUsername(name) ?: throw UnauthorizedException("認証ユーザーが見つかりません")
}
