package jp.yukio0.kakeibo.trusteddevice

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import jp.yukio0.kakeibo.user.AppUserEntity
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TrustedDeviceService(
  private val trustedDeviceRepository: TrustedDeviceRepository,
  @Value("\${server.servlet.session.cookie.secure:false}") private val secureCookie: Boolean,
  private val clock: Clock = Clock.systemUTC(),
) {

  private val secureRandom = SecureRandom()

  @Transactional
  fun isTrustedDevice(
    appUser: AppUserEntity,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ): Boolean {
    val token = request.trustedDeviceToken() ?: return false
    val tokenHash = hashToken(token)
    val trustedDevice =
      trustedDeviceRepository.findByTokenHashAndAppUser(tokenHash, appUser)
        ?: run {
          clearTrustedDeviceCookie(response)
          return false
        }

    val now = Instant.now(clock)
    if (!trustedDevice.expiresAt.isAfter(now)) {
      trustedDeviceRepository.delete(trustedDevice)
      clearTrustedDeviceCookie(response)
      return false
    }

    trustedDevice.lastUsedAt = now
    trustedDeviceRepository.save(trustedDevice)
    return true
  }

  @Transactional
  fun trustCurrentDevice(
    appUser: AppUserEntity,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ) {
    val token = generateToken()
    val now = Instant.now(clock)
    val expiresAt = now.plus(TRUST_DURATION)

    trustedDeviceRepository.save(
      TrustedDeviceEntity(
        appUser = appUser,
        tokenHash = hashToken(token),
        deviceName = request.deviceName(),
        lastUsedAt = now,
        expiresAt = expiresAt,
      )
    )

    writeTrustedDeviceCookie(
      response = response,
      value = token,
      maxAge = TRUST_DURATION,
    )
  }

  @Transactional
  fun listTrustedDevices(
    appUser: AppUserEntity,
    request: HttpServletRequest,
  ): List<TrustedDeviceResponse> {
    removeExpiredTrustedDevices(appUser)
    val currentTokenHash = request.trustedDeviceToken()?.let(::hashToken)

    return trustedDeviceRepository.findAllByAppUserOrderByCreatedAtDesc(appUser).map { trustedDevice
      ->
      trustedDevice.toResponse(current = trustedDevice.tokenHash == currentTokenHash)
    }
  }

  @Transactional
  fun revokeTrustedDevice(
    appUser: AppUserEntity,
    id: Long,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ) {
    val currentTokenHash = request.trustedDeviceToken()?.let(::hashToken)
    val trustedDevice = trustedDeviceRepository.findById(id).orElse(null) ?: return
    if (trustedDevice.appUser.id != appUser.id) {
      return
    }
    trustedDeviceRepository.delete(trustedDevice)

    if (trustedDevice.tokenHash == currentTokenHash) {
      clearTrustedDeviceCookie(response)
    }
  }

  @Transactional
  fun revokeCurrentTrustedDevice(
    appUser: AppUserEntity,
    request: HttpServletRequest,
    response: HttpServletResponse,
  ) {
    request.trustedDeviceToken()?.let { token ->
      trustedDeviceRepository.deleteByTokenHashAndAppUser(hashToken(token), appUser)
    }
    clearTrustedDeviceCookie(response)
  }

  @Transactional
  fun revokeAllTrustedDevices(
    appUser: AppUserEntity,
    response: HttpServletResponse? = null,
  ) {
    trustedDeviceRepository.deleteAllByAppUser(appUser)
    if (response != null) {
      clearTrustedDeviceCookie(response)
    }
  }

  private fun removeExpiredTrustedDevices(appUser: AppUserEntity) {
    val now = Instant.now(clock)
    trustedDeviceRepository
      .findAllByAppUserOrderByCreatedAtDesc(appUser)
      .filter { !it.expiresAt.isAfter(now) }
      .forEach {
        trustedDeviceRepository.delete(it)
      }
  }

  private fun TrustedDeviceEntity.toResponse(current: Boolean): TrustedDeviceResponse =
    TrustedDeviceResponse(
      id = requireNotNull(id),
      deviceName = deviceName,
      lastUsedAt = lastUsedAt,
      expiresAt = expiresAt,
      createdAt = createdAt,
      current = current,
    )

  private fun generateToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun hashToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }
  }

  private fun HttpServletRequest.trustedDeviceToken(): String? =
    cookies
      ?.firstOrNull { it.name == TRUSTED_DEVICE_COOKIE_NAME }
      ?.value
      ?.takeIf {
        it.isNotBlank()
      }

  private fun HttpServletRequest.deviceName(): String =
    getHeader(HttpHeaders.USER_AGENT)?.take(MAX_DEVICE_NAME_LENGTH) ?: "不明な端末"

  private fun writeTrustedDeviceCookie(
    response: HttpServletResponse,
    value: String,
    maxAge: Duration,
  ) {
    val cookie =
      ResponseCookie.from(TRUSTED_DEVICE_COOKIE_NAME, value)
        .httpOnly(true)
        .secure(secureCookie)
        .sameSite("Strict")
        .path("/")
        .maxAge(maxAge)
        .build()
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
  }

  /** DB側の失効処理が成功した後に、呼び出し側からブラウザのCookieだけを削除する。 */
  fun clearTrustedDeviceCookie(response: HttpServletResponse) {
    writeTrustedDeviceCookie(
      response = response,
      value = "",
      maxAge = Duration.ZERO,
    )
  }

  companion object {
    const val TRUSTED_DEVICE_COOKIE_NAME = "KAKEIBO_TRUSTED_DEVICE"
    private const val TOKEN_BYTES = 32
    private const val MAX_DEVICE_NAME_LENGTH = 255
    private val TRUST_DURATION: Duration = Duration.ofDays(30)
  }
}
