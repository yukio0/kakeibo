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
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
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
  private val passwordEncoder: PasswordEncoder,
  private val securityContextRepository: SecurityContextRepository,
  private val trustedDeviceService: TrustedDeviceService,
  private val securityFeatureProperties: SecurityFeatureProperties,
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

    val authentication =
      try {
        authenticationManager.authenticate(
          UsernamePasswordAuthenticationToken(
            request.username?.trim(),
            request.password,
          )
        )
      } catch (exception: AuthenticationException) {
        throw UnauthorizedException("ユーザー名またはパスワードが正しくありません")
      }

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

    val appUser = authentication?.toAppUser() ?: throw UnauthorizedException("認証が必要です")

    val errors = mutableListOf<ApiFieldErrorResponse>()
    val currentPassword = request.currentPassword ?: ""
    val newPassword = request.newPassword ?: ""
    val newPasswordConfirm = request.newPasswordConfirm ?: ""

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
    trustedDeviceService.revokeAllTrustedDevices(appUser, httpResponse)
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
