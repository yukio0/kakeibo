package jp.yukio0.kakeibo.auth

import java.util.UUID
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(properties = ["kakeibo.security.authentication-enabled=false"])
@ActiveProfiles("test")
class AuthenticationDisabledApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  private val mockMvc: MockMvc by lazy {
    MockMvcBuilders.webAppContextSetup(context)
      .apply<DefaultMockMvcBuilder>(springSecurity())
      .build()
  }

  @Test
  fun meReturnsDisabledAuthenticationUserWithoutSession() {
    mockMvc
      .perform(get("/api/me"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.username").value("認証無効"))
      .andExpect(jsonPath("$.twoFactorEnabled").value(false))
  }

  @Test
  fun protectedApiIsAccessibleWithoutAuthentication() {
    mockMvc.perform(get("/api/categories")).andExpect(status().isOk)
  }

  @Test
  fun loginDoesNotAuthenticateAndNeverRequiresMfa() {
    mockMvc
      .perform(
        post("/api/login")
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "username": "unknown-user",
              "password": "unknown-password"
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.mfaRequired").value(false))
      .andExpect(jsonPath("$.user.username").value("認証無効"))
      .andExpect(jsonPath("$.user.twoFactorEnabled").value(false))
  }

  @Test
  fun mfaIsDisabledWhenAuthenticationIsDisabled() {
    mockMvc
      .perform(get("/api/mfa/status"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.enabled").value(false))

    mockMvc.perform(get("/api/mfa/setup")).andExpect(status().isBadRequest)
  }
}

@SpringBootTest(properties = ["kakeibo.security.two-factor-enabled=false"])
@ActiveProfiles("test")
class TwoFactorDisabledApiTests {

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
  fun loginSkipsMfaWhenTwoFactorFeatureIsDisabled() {
    val username =
      createTestUser(
        twoFactorEnabled = true,
        twoFactorSecret = totpService.generateSecret(),
      )

    val session = login(username)

    mockMvc
      .perform(get("/api/me").session(session))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.username").value(username))
      .andExpect(jsonPath("$.twoFactorEnabled").value(false))
  }

  @Test
  fun mfaEndpointsExposeDisabledStatusWhenTwoFactorFeatureIsDisabled() {
    val session = login(createTestUser(twoFactorEnabled = false))

    mockMvc
      .perform(get("/api/mfa/status").session(session))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.enabled").value(false))

    mockMvc.perform(get("/api/mfa/setup").session(session)).andExpect(status().isBadRequest)
  }

  private fun login(username: String): MockHttpSession {
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
                "password": "$TEST_PASSWORD"
              }
              """
                .trimIndent()
            )
        )
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.mfaRequired").value(false))
        .andReturn()

    return result.request.session as MockHttpSession
  }

  private fun createTestUser(
    twoFactorEnabled: Boolean,
    twoFactorSecret: String? = null,
  ): String {
    val username = "security-feature-${UUID.randomUUID()}"
    appUserRepository.save(
      AppUserEntity(
        username = username,
        passwordHash = passwordEncoder.encode(TEST_PASSWORD) ?: error("Password hash is empty"),
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
