package jp.yukio0.kakeibo.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.UnauthorizedException
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
) {

  @PostMapping("/login")
  fun login(
    @Valid @RequestBody request: LoginRequest,
    httpRequest: HttpServletRequest,
    httpResponse: HttpServletResponse,
  ): AuthUserResponse {
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

    val session = httpRequest.getSession(true)
    if (!session.isNew) {
      httpRequest.changeSessionId()
    }

    val securityContext = SecurityContextHolder.createEmptyContext()
    securityContext.authentication = authentication
    SecurityContextHolder.setContext(securityContext)
    securityContextRepository.saveContext(securityContext, httpRequest, httpResponse)

    return authentication.toResponse()
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun logout(httpRequest: HttpServletRequest, httpResponse: HttpServletResponse) {
    SecurityContextLogoutHandler()
      .logout(httpRequest, httpResponse, SecurityContextHolder.getContext().authentication)
  }

  @GetMapping("/me")
  fun me(authentication: Authentication?): AuthUserResponse =
    authentication?.toResponse() ?: throw UnauthorizedException("認証が必要です")

  @PutMapping("/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun changePassword(
    authentication: Authentication?,
    @Valid @RequestBody request: ChangePasswordRequest,
  ) {
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
  }

  @GetMapping("/csrf")
  fun csrf(csrfToken: CsrfToken): CsrfResponse =
    CsrfResponse(
      headerName = csrfToken.headerName,
      parameterName = csrfToken.parameterName,
      token = csrfToken.token,
    )

  private fun Authentication.toResponse(): AuthUserResponse {
    val appUser = toAppUser()

    return AuthUserResponse(
      username = appUser.username,
      twoFactorEnabled = appUser.twoFactorEnabled,
    )
  }

  private fun Authentication.toAppUser(): AppUserEntity =
    appUserRepository.findByUsername(name) ?: throw UnauthorizedException("認証ユーザーが見つかりません")
}
