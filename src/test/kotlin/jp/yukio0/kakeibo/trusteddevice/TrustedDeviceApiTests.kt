package jp.yukio0.kakeibo.trusteddevice

import jakarta.servlet.http.Cookie
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
class TrustedDeviceApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var appUserRepository: AppUserRepository

  @Autowired private lateinit var passwordEncoder: PasswordEncoder

  @Autowired private lateinit var trustedDeviceRepository: TrustedDeviceRepository

  private val mockMvc: MockMvc by lazy {
    MockMvcBuilders.webAppContextSetup(context)
      .apply<DefaultMockMvcBuilder>(springSecurity())
      .build()
  }

  @Test
  fun listTrustedDevicesMarksCurrentDevice() {
    val username = createTestUser()
    val session = login(username)
    val appUser = findUser(username)
    val token = newToken()
    createTrustedDevice(appUser = appUser, token = token, deviceName = "JUnit Device")

    mockMvc
      .perform(get("/api/trusted-devices").session(session).cookie(trustedDeviceCookie(token)))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.length()").value(1))
      .andExpect(jsonPath("$[0].deviceName").value("JUnit Device"))
      .andExpect(jsonPath("$[0].current").value(true))
  }

  @Test
  fun revokeCurrentTrustedDeviceRemovesTokenAndClearsCookie() {
    val username = createTestUser()
    val session = login(username)
    val appUser = findUser(username)
    val token = newToken()
    createTrustedDevice(appUser = appUser, token = token)

    mockMvc
      .perform(
        delete("/api/trusted-devices/current")
          .session(session)
          .cookie(trustedDeviceCookie(token))
          .with(csrf())
      )
      .andExpect(status().isNoContent)
      .andExpect(
        header()
          .string(
            HttpHeaders.SET_COOKIE,
            org.hamcrest.Matchers.containsString(
              "${TrustedDeviceService.TRUSTED_DEVICE_COOKIE_NAME}=;"
            ),
          )
      )

    assertNull(trustedDeviceRepository.findByTokenHashAndAppUser(hashToken(token), appUser))
  }

  @Test
  fun revokeTrustedDeviceByIdRemovesOnlyTargetDevice() {
    val username = createTestUser()
    val session = login(username)
    val appUser = findUser(username)
    val target = createTrustedDevice(appUser = appUser, token = newToken(), deviceName = "target")
    val remaining =
      createTrustedDevice(appUser = appUser, token = newToken(), deviceName = "remaining")

    mockMvc
      .perform(delete("/api/trusted-devices/{id}", target.id).session(session).with(csrf()))
      .andExpect(status().isNoContent)

    assertNull(trustedDeviceRepository.findById(requireNotNull(target.id)).orElse(null))
    org.junit.jupiter.api.Assertions.assertNotNull(
      trustedDeviceRepository.findById(requireNotNull(remaining.id)).orElse(null)
    )
  }

  @Test
  fun revokeAllTrustedDevicesClearsAllAndCurrentCookie() {
    val username = createTestUser()
    val session = login(username)
    val appUser = findUser(username)
    val token = newToken()
    val anotherToken = newToken()
    createTrustedDevice(appUser = appUser, token = token)
    createTrustedDevice(appUser = appUser, token = anotherToken)

    mockMvc
      .perform(
        delete("/api/trusted-devices")
          .session(session)
          .cookie(trustedDeviceCookie(token))
          .with(csrf())
      )
      .andExpect(status().isNoContent)
      .andExpect(
        header()
          .string(
            HttpHeaders.SET_COOKIE,
            org.hamcrest.Matchers.containsString(
              "${TrustedDeviceService.TRUSTED_DEVICE_COOKIE_NAME}=;"
            ),
          )
      )

    assertNull(trustedDeviceRepository.findByTokenHashAndAppUser(hashToken(token), appUser))
    assertNull(trustedDeviceRepository.findByTokenHashAndAppUser(hashToken(anotherToken), appUser))
  }

  @Test
  fun passwordChangeInvalidatesTrustedDevices() {
    val username = createTestUser(password = "old-password")
    val session = login(username, password = "old-password")
    val appUser = findUser(username)
    val token = newToken()
    createTrustedDevice(appUser = appUser, token = token)

    mockMvc
      .perform(
        put("/api/me/password")
          .session(session)
          .cookie(trustedDeviceCookie(token))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "currentPassword": "old-password",
              "newPassword": "new-password",
              "newPasswordConfirm": "new-password"
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isNoContent)

    assertNull(trustedDeviceRepository.findByTokenHashAndAppUser(hashToken(token), appUser))
  }

  private fun createTrustedDevice(
    appUser: AppUserEntity,
    token: String,
    deviceName: String = "JUnit Device",
  ): TrustedDeviceEntity =
    trustedDeviceRepository.save(
      TrustedDeviceEntity(
        appUser = appUser,
        tokenHash = hashToken(token),
        deviceName = deviceName,
        lastUsedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        expiresAt = Instant.now().plus(30, ChronoUnit.DAYS),
      )
    )

  private fun login(
    username: String,
    password: String = TEST_PASSWORD,
  ): MockHttpSession {
    val result =
      mockMvc
        .perform(
          post("/api/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
              """
              {
                "username": "$username",
                "password": "$password"
              }
              """
                .trimIndent()
            )
        )
        .andExpect(status().isOk)
        .andReturn()

    return result.request.session as MockHttpSession
  }

  private fun createTestUser(password: String = TEST_PASSWORD): String {
    val username = "trusted-device-${UUID.randomUUID()}"
    appUserRepository.save(
      AppUserEntity(
        username = username,
        passwordHash = passwordEncoder.encode(password) ?: error("Password hash is empty"),
      )
    )
    return username
  }

  private fun findUser(username: String): AppUserEntity =
    appUserRepository.findByUsername(username) ?: error("App user is not found: $username")

  private fun trustedDeviceCookie(token: String): Cookie =
    Cookie(TrustedDeviceService.TRUSTED_DEVICE_COOKIE_NAME, token)

  private fun newToken(): String = "trusted-token-${UUID.randomUUID()}"

  private fun hashToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }
  }

  private companion object {
    private const val TEST_PASSWORD = "test-password"
  }
}
