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

  @Autowired private lateinit var mfaRecoveryCodeRepository: MfaRecoveryCodeRepository

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
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.recoveryCodes.length()").value(RECOVERY_CODE_COUNT))

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
  fun enableIssuesRecoveryCodesAsHashesOnly() {
    val username = createTestUser()
    val session = login(username)
    val secret = setup(session)

    val recoveryCodes = enable(session, totpService.generateCode(secret))

    assertEquals(RECOVERY_CODE_COUNT, recoveryCodes.size)
    assertEquals(RECOVERY_CODE_COUNT, recoveryCodes.distinct().size)
    recoveryCodes.forEach { assertTrue(it.matches(RECOVERY_CODE_FORMAT)) }

    val appUser = assertNotNull(appUserRepository.findByUsername(username))
    val storedCodes = mfaRecoveryCodeRepository.findAllByAppUserAndUsedAtIsNullOrderByIdAsc(appUser)
    assertEquals(RECOVERY_CODE_COUNT, storedCodes.size)
    // 平文はDBに残さず、照合できるハッシュだけを保存する
    val plainCodes = recoveryCodes.map { it.replace("-", "") }
    storedCodes.forEach { stored ->
      assertFalse(plainCodes.contains(stored.codeHash))
      assertTrue(plainCodes.any { passwordEncoder.matches(it, stored.codeHash) })
    }
  }

  @Test
  fun recoveryCodeStatusReportsRemainingCount() {
    val username = createTestUser()
    val session = login(username)
    val secret = setup(session)
    enable(session, totpService.generateCode(secret))

    mockMvc
      .perform(get("/api/mfa/recovery-codes").session(session))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.total").value(RECOVERY_CODE_COUNT))
      .andExpect(jsonPath("$.remaining").value(RECOVERY_CODE_COUNT))
  }

  @Test
  fun regenerateReplacesPreviousRecoveryCodes() {
    val username = createTestUser()
    val session = login(username)
    val secret = setup(session)
    val firstCodes = enable(session, totpService.generateCode(secret))

    val responseBody =
      mockMvc
        .perform(post("/api/mfa/recovery-codes").session(session).with(csrf()))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.recoveryCodes.length()").value(RECOVERY_CODE_COUNT))
        .andReturn()
        .response
        .contentAsString
    val secondCodes = extractRecoveryCodes(responseBody)

    assertTrue(firstCodes.intersect(secondCodes.toSet()).isEmpty())

    val appUser = assertNotNull(appUserRepository.findByUsername(username))
    val storedCodes = mfaRecoveryCodeRepository.findAllByAppUserAndUsedAtIsNullOrderByIdAsc(appUser)
    assertEquals(RECOVERY_CODE_COUNT, storedCodes.size)
    // 古いコードは1つも残さない
    firstCodes.forEach { oldCode ->
      assertFalse(
        storedCodes.any { passwordEncoder.matches(oldCode.replace("-", ""), it.codeHash) }
      )
    }
  }

  @Test
  fun regenerateRejectsUsersWithoutTwoFactor() {
    val username = createTestUser()
    val session = login(username)

    mockMvc
      .perform(post("/api/mfa/recovery-codes").session(session).with(csrf()))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("2段階認証を有効にしてください"))
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
  fun disableDiscardsRecoveryCodes() {
    val username = createTestUser()
    val session = login(username)
    val secret = setup(session)
    enable(session, totpService.generateCode(secret))

    mockMvc
      .perform(post("/api/mfa/disable").session(session).with(csrf()))
      .andExpect(status().isNoContent)

    val appUser = assertNotNull(appUserRepository.findByUsername(username))
    assertEquals(0, mfaRecoveryCodeRepository.countByAppUser(appUser))
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

  private fun enable(session: MockHttpSession, code: String): List<String> {
    val responseBody =
      mockMvc
        .perform(
          post("/api/mfa/enable")
            .session(session)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(codeJson(code))
        )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .contentAsString
    return extractRecoveryCodes(responseBody)
  }

  private fun extractRecoveryCodes(json: String): List<String> =
    Regex(""""recoveryCodes"\s*:\s*\[([^\]]*)]""").find(json)?.groupValues?.get(1)?.let { array ->
      Regex(""""([^"]+)"""").findAll(array).map { it.groupValues[1] }.toList()
    } ?: error("recoveryCodes is not found")

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
    private const val RECOVERY_CODE_COUNT = 10
    // 見間違えやすい 0/1/I/L/O を含まない英数字10文字を、中央でハイフン区切りにした形
    private val RECOVERY_CODE_FORMAT = Regex("[2-9A-HJKMNP-Z]{5}-[2-9A-HJKMNP-Z]{5}")
  }
}
