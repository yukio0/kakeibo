package jp.yukio0.kakeibo.auth

import java.util.UUID
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
class MfaApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var appUserRepository: AppUserRepository

  @Autowired private lateinit var passwordEncoder: PasswordEncoder

  @Autowired private lateinit var totpService: TotpService

  private val mockMvc: MockMvc by lazy {
    MockMvcBuilders.webAppContextSetup(context)
      .apply<DefaultMockMvcBuilder>(springSecurity())
      .build()
  }

  @Test
  fun statusReturnsDisabledByDefault() {
    val username = createTestUser()
    val session = login(username)

    mockMvc
      .perform(get("/api/mfa/status").session(session))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.enabled").value(false))
  }

  @Test
  fun setupReturnsSecretAndQrWithoutEnablingMfa() {
    val username = createTestUser()
    val session = login(username)

    val responseBody =
      mockMvc
        .perform(get("/api/mfa/setup").session(session))
        .andExpect(status().isOk)
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.secret").isNotEmpty)
        .andExpect(jsonPath("$.otpauthUri").isNotEmpty)
        .andExpect(jsonPath("$.qrCodeSvg").isNotEmpty)
        .andReturn()
        .response
        .contentAsString

    val secret = extractString(responseBody, "secret")
    val otpAuthUri = extractString(responseBody, "otpauthUri")
    val qrCodeSvg = extractString(responseBody, "qrCodeSvg")

    assertTrue(secret.matches(Regex("[A-Z2-7]+")))
    assertTrue(otpAuthUri.startsWith("otpauth://totp/Kakeibo%3A"))
    assertTrue(qrCodeSvg.contains("&lt;svg").not())
    assertTrue(qrCodeSvg.contains("<svg"))

    val appUser = appUserRepository.findByUsername(username)
    assertNotNull(appUser)
    assertFalse(appUser.twoFactorEnabled)
    assertNull(appUser.twoFactorSecret)
  }

  @Test
  fun enableStoresPendingSecretAfterValidCode() {
    val username = createTestUser()
    val session = login(username)
    val secret = setup(session)
    val code = totpService.generateCode(secret)

    mockMvc
      .perform(
        post("/api/mfa/enable")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(codeJson(code))
      )
      .andExpect(status().isNoContent)

    val appUser = appUserRepository.findByUsername(username)
    assertNotNull(appUser)
    assertTrue(appUser.twoFactorEnabled)
    assertEquals(secret, appUser.twoFactorSecret)

    mockMvc
      .perform(get("/api/mfa/status").session(session))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.enabled").value(true))
  }

  @Test
  fun enableRejectsInvalidCodeAndDoesNotEnableMfa() {
    val username = createTestUser()
    val session = login(username)
    val secret = setup(session)
    val invalidCode = if (totpService.generateCode(secret) == "000000") "000001" else "000000"

    mockMvc
      .perform(
        post("/api/mfa/enable")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(codeJson(invalidCode))
      )
      .andExpect(status().isBadRequest)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.errors[0].field").value("code"))
      .andExpect(jsonPath("$.errors[0].message").value("確認コードが正しくありません"))

    val appUser = appUserRepository.findByUsername(username)
    assertNotNull(appUser)
    assertFalse(appUser.twoFactorEnabled)
    assertNull(appUser.twoFactorSecret)
  }

  @Test
  fun enableRejectsMissingSetup() {
    val username = createTestUser()
    val session = login(username)

    mockMvc
      .perform(
        post("/api/mfa/enable")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(codeJson("123456"))
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("2段階認証の設定を開始してください"))
  }

  @Test
  fun disableClearsSecret() {
    val username = createTestUser(twoFactorEnabled = true, twoFactorSecret = "ABCDEFGHIJKLMNOP")
    val session = login(username)

    mockMvc
      .perform(post("/api/mfa/disable").session(session).with(csrf()))
      .andExpect(status().isNoContent)

    val appUser = appUserRepository.findByUsername(username)
    assertNotNull(appUser)
    assertFalse(appUser.twoFactorEnabled)
    assertNull(appUser.twoFactorSecret)
  }

  @Test
  fun mfaMutationsRejectMissingCsrfToken() {
    val username = createTestUser()
    val session = login(username)

    mockMvc
      .perform(
        post("/api/mfa/enable")
          .session(session)
          .contentType(MediaType.APPLICATION_JSON)
          .content(codeJson("123456"))
      )
      .andExpect(status().isForbidden)
  }

  private fun setup(session: MockHttpSession): String {
    val responseBody =
      mockMvc
        .perform(get("/api/mfa/setup").session(session))
        .andExpect(status().isOk)
        .andReturn()
        .response
        .contentAsString
    return extractString(responseBody, "secret")
  }

  private fun login(username: String, password: String = TEST_PASSWORD): MockHttpSession {
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

    val session = result.request.session as MockHttpSession
    val appUser = appUserRepository.findByUsername(username)
    if (appUser?.twoFactorEnabled == true && !appUser.twoFactorSecret.isNullOrBlank()) {
      val code = totpService.generateCode(appUser.twoFactorSecret!!)
      val verifyResult =
        mockMvc
          .perform(
            post("/api/mfa/verify")
              .session(session)
              .with(csrf())
              .contentType(MediaType.APPLICATION_JSON)
              .content(codeJson(code))
          )
          .andExpect(status().isOk)
          .andReturn()
      return verifyResult.request.session as MockHttpSession
    }

    return session
  }

  private fun codeJson(code: String): String =
    """
    {
      "code": "$code"
    }
    """
      .trimIndent()

  private fun createTestUser(
    password: String = TEST_PASSWORD,
    twoFactorEnabled: Boolean = false,
    twoFactorSecret: String? = null,
  ): String {
    val username = "mfa-${UUID.randomUUID()}"
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

  private fun extractString(json: String, field: String): String =
    Regex(""""$field"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
      ?: error("$field is not found")

  private companion object {
    private const val TEST_PASSWORD = "test-password"
  }
}
