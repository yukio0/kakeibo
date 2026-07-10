package jp.yukio0.kakeibo.auth

import jakarta.servlet.http.Cookie
import java.util.UUID
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceRepository
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceService
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
class MfaLoginApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var appUserRepository: AppUserRepository

  @Autowired private lateinit var passwordEncoder: PasswordEncoder

  @Autowired private lateinit var totpService: TotpService

  @Autowired private lateinit var trustedDeviceRepository: TrustedDeviceRepository

  private val mockMvc: MockMvc by lazy {
    MockMvcBuilders.webAppContextSetup(context)
      .apply<DefaultMockMvcBuilder>(springSecurity())
      .build()
  }

  @Test
  fun loginForMfaDisabledUserCompletesWithPasswordOnly() {
    val username = createTestUser(twoFactorEnabled = false)
    val session = login(username)

    mockMvc
      .perform(get("/api/me").session(session))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.username").value(username))
      .andExpect(jsonPath("$.twoFactorEnabled").value(false))
  }

  @Test
  fun loginForMfaEnabledUserRequiresTotpAndBlocksProtectedApisUntilVerified() {
    val secret = totpService.generateSecret()
    val username = createTestUser(twoFactorEnabled = true, twoFactorSecret = secret)
    val session = login(username, expectMfaRequired = true)

    mockMvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized)
    mockMvc.perform(get("/api/categories").session(session)).andExpect(status().isUnauthorized)
  }

  @Test
  fun verifyMfaCompletesLoginAndAllowsProtectedApisInSameSession() {
    val secret = totpService.generateSecret()
    val username = createTestUser(twoFactorEnabled = true, twoFactorSecret = secret)
    val session = login(username, expectMfaRequired = true)
    val code = totpService.generateCode(secret)

    val verifiedSession =
      mockMvc
        .perform(
          post("/api/mfa/verify")
            .session(session)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(codeJson(code))
        )
        .andExpect(status().isOk)
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.twoFactorEnabled").value(true))
        .andReturn()
        .request
        .session as MockHttpSession

    mockMvc
      .perform(get("/api/me").session(verifiedSession))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.username").value(username))
  }

  @Test
  fun trustedDeviceSkipsTotpOnNextLogin() {
    val secret = totpService.generateSecret()
    val username = createTestUser(twoFactorEnabled = true, twoFactorSecret = secret)
    val session = login(username, expectMfaRequired = true)
    val code = totpService.generateCode(secret)

    val verifyResponse =
      mockMvc
        .perform(
          post("/api/mfa/verify")
            .session(session)
            .with(csrf())
            .header(HttpHeaders.USER_AGENT, "JUnit Browser")
            .contentType(MediaType.APPLICATION_JSON)
            .content(codeJson(code = code, trustDevice = true))
        )
        .andExpect(status().isOk)
        .andReturn()
        .response

    val trustedDeviceCookie =
      extractTrustedDeviceCookie(verifyResponse.getHeaders(HttpHeaders.SET_COOKIE))
    assertNotNull(trustedDeviceCookie)
    assertTrue(trustedDeviceRepository.findAll().any { it.deviceName == "JUnit Browser" })

    val trustedLoginResult =
      mockMvc
        .perform(
          post("/api/login")
            .cookie(trustedDeviceCookie)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(username, TEST_PASSWORD))
        )
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.mfaRequired").value(false))
        .andExpect(jsonPath("$.user.username").value(username))
        .andReturn()

    mockMvc
      .perform(get("/api/me").session(trustedLoginResult.request.session as MockHttpSession))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.username").value(username))
  }

  @Test
  fun trustedDeviceDoesNotSkipTotpForDifferentUser() {
    val firstSecret = totpService.generateSecret()
    val firstUsername = createTestUser(twoFactorEnabled = true, twoFactorSecret = firstSecret)
    val firstSession = login(firstUsername, expectMfaRequired = true)

    val verifyResponse =
      mockMvc
        .perform(
          post("/api/mfa/verify")
            .session(firstSession)
            .with(csrf())
            .header(HttpHeaders.USER_AGENT, "JUnit Browser")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
              codeJson(
                code = totpService.generateCode(firstSecret),
                trustDevice = true,
              )
            )
        )
        .andExpect(status().isOk)
        .andReturn()
        .response

    val trustedDeviceCookie =
      extractTrustedDeviceCookie(verifyResponse.getHeaders(HttpHeaders.SET_COOKIE))
    assertNotNull(trustedDeviceCookie)

    val secondSecret = totpService.generateSecret()
    val secondUsername = createTestUser(twoFactorEnabled = true, twoFactorSecret = secondSecret)

    mockMvc
      .perform(
        post("/api/login")
          .cookie(trustedDeviceCookie)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(loginJson(secondUsername, TEST_PASSWORD))
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.mfaRequired").value(true))
  }

  @Test
  fun verifyMfaRejectsInvalidCodeAndKeepsProtectedApisBlocked() {
    val secret = totpService.generateSecret()
    val username = createTestUser(twoFactorEnabled = true, twoFactorSecret = secret)
    val session = login(username, expectMfaRequired = true)
    val invalidCode = if (totpService.generateCode(secret) == "000000") "000001" else "000000"

    mockMvc
      .perform(
        post("/api/mfa/verify")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(codeJson(invalidCode))
      )
      .andExpect(status().isBadRequest)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.errors[0].field").value("code"))
      .andExpect(jsonPath("$.errors[0].message").value("確認コードが正しくありません"))

    mockMvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized)
  }

  @Test
  fun verifyMfaRejectsMissingPendingLogin() {
    mockMvc
      .perform(
        post("/api/mfa/verify")
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(codeJson("123456"))
      )
      .andExpect(status().isUnauthorized)
  }

  @Test
  fun verifyMfaRequiresCsrfToken() {
    val secret = totpService.generateSecret()
    val username = createTestUser(twoFactorEnabled = true, twoFactorSecret = secret)
    val session = login(username, expectMfaRequired = true)

    mockMvc
      .perform(
        post("/api/mfa/verify")
          .session(session)
          .contentType(MediaType.APPLICATION_JSON)
          .content(codeJson(totpService.generateCode(secret)))
      )
      .andExpect(status().isForbidden)
  }

  private fun login(
    username: String,
    password: String = TEST_PASSWORD,
    expectMfaRequired: Boolean = false,
  ): MockHttpSession {
    val result =
      mockMvc
        .perform(
          post("/api/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(username, password))
        )
        .andExpect(status().isOk)
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.mfaRequired").value(expectMfaRequired))
        .andReturn()

    if (expectMfaRequired) {
      mockMvc
        .perform(get("/api/me").session(result.request.session as MockHttpSession))
        .andExpect(status().isUnauthorized)
    } else {
      mockMvc
        .perform(get("/api/me").session(result.request.session as MockHttpSession))
        .andExpect(status().isOk)
    }

    return result.request.session as MockHttpSession
  }

  private fun loginJson(username: String, password: String): String =
    """
    {
      "username": "$username",
      "password": "$password"
    }
    """
      .trimIndent()

  private fun codeJson(
    code: String,
    trustDevice: Boolean = false,
  ): String =
    """
    {
      "code": "$code",
      "trustDevice": $trustDevice
    }
    """
      .trimIndent()

  private fun extractTrustedDeviceCookie(setCookieHeaders: Collection<String>): Cookie? {
    val value =
      setCookieHeaders
        .firstOrNull { it.startsWith("${TrustedDeviceService.TRUSTED_DEVICE_COOKIE_NAME}=") }
        ?.substringAfter("=")
        ?.substringBefore(";")
        ?.takeIf { it.isNotBlank() } ?: return null
    return Cookie(TrustedDeviceService.TRUSTED_DEVICE_COOKIE_NAME, value)
  }

  private fun createTestUser(
    password: String = TEST_PASSWORD,
    twoFactorEnabled: Boolean,
    twoFactorSecret: String? = null,
  ): String {
    val username = "mfa-login-${UUID.randomUUID()}"
    appUserRepository.save(
      AppUserEntity(
        username = username,
        passwordHash = passwordEncoder.encode(password) ?: error("Password hash is empty"),
        twoFactorEnabled = twoFactorEnabled,
        twoFactorSecret = twoFactorSecret,
      )
    )
    return username
  }

  private companion object {
    private const val TEST_PASSWORD = "test-password"
  }
}
