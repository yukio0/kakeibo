package jp.yukio0.kakeibo.auth

import java.util.UUID
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import kotlin.test.assertNotEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
class AuthApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var appUserRepository: AppUserRepository

  @Autowired private lateinit var passwordEncoder: PasswordEncoder

  private val mockMvc: MockMvc by lazy {
    MockMvcBuilders.webAppContextSetup(context)
      .apply<DefaultMockMvcBuilder>(springSecurity())
      .build()
  }

  @Test
  fun csrfEndpointReturnsToken() {
    mockMvc
      .perform(get("/api/csrf"))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.headerName").isNotEmpty)
      .andExpect(jsonPath("$.parameterName").isNotEmpty)
      .andExpect(jsonPath("$.token").isNotEmpty)
  }

  @Test
  fun pwaAssetsAreReachableWithoutAuthentication() {
    // manifest とアイコンは未認証でも取得できる必要がある。テスト用classpathに実体は無いので、
    // 401(認証で拒否)ではなく 404(認証を通過して未検出)になることで permitAll を検証する。
    listOf("/site.webmanifest", "/icon-192.png", "/icon-512.png", "/apple-touch-icon.png")
      .forEach { path ->
        mockMvc.perform(get(path)).andExpect(status().isNotFound)
      }
  }

  @Test
  fun responsesCarryNoindexRobotsHeader() {
    // 認証前の公開エンドポイントにも、保護済みAPIにも noindex が付くことを確認する
    mockMvc
      .perform(get("/api/csrf"))
      .andExpect(status().isOk)
      .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))

    mockMvc
      .perform(get("/api/me").session(login()))
      .andExpect(status().isOk)
      .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
  }

  @Test
  fun loginSucceedsAndMeReturnsUser() {
    val session = login()

    mockMvc
      .perform(get("/api/me").session(session))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.username").value(TEST_USERNAME))
      .andExpect(jsonPath("$.twoFactorEnabled").value(false))
  }

  @Test
  fun loginRejectsWrongPassword() {
    mockMvc
      .perform(
        post("/api/login")
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(loginJson(password = "wrong-password"))
      )
      .andExpect(status().isUnauthorized)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.message").value("ユーザー名またはパスワードが正しくありません"))
  }

  @Test
  fun loginLocksOutAfterRepeatedFailures() {
    val username = "throttle-${UUID.randomUUID()}"

    // MAX_FAILURES 回の失敗は通常の 401
    repeat(AuthThrottleService.MAX_FAILURES) {
      mockMvc
        .perform(
          post("/api/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(username = username, password = "wrong-password"))
        )
        .andExpect(status().isUnauthorized)
    }

    // 以降はロックされ 429 + Retry-After
    mockMvc
      .perform(
        post("/api/login")
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(loginJson(username = username, password = "wrong-password"))
      )
      .andExpect(status().isTooManyRequests)
      .andExpect(
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
          .exists("Retry-After")
      )
  }

  @Test
  fun logoutInvalidatesSession() {
    val session = login()

    mockMvc
      .perform(post("/api/logout").session(session).with(csrf()))
      .andExpect(status().isNoContent)

    mockMvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized)
  }

  @Test
  fun protectedApiRequiresAuthentication() {
    mockMvc.perform(get("/api/categories")).andExpect(status().isUnauthorized)
  }

  @Test
  fun recurringTemplatePageForwardsToSpaWithoutAuthentication() {
    mockMvc
      .perform(get("/recurring-templates"))
      .andExpect(status().isOk)
      .andExpect(forwardedUrl("/index.html"))
  }

  @Test
  fun mutatingApiRejectsMissingCsrfToken() {
    val session = login()

    mockMvc
      .perform(
        post("/api/categories")
          .session(session)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "CSRFテスト",
              "type": "EXPENSE",
              "displayOrder": 999
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isForbidden)
  }

  @Test
  fun initialUserPasswordIsStoredAsHash() {
    val appUser =
      appUserRepository.findByUsername(TEST_USERNAME) ?: error("Initial user is not found")

    assertNotEquals(TEST_PASSWORD, appUser.passwordHash)
    assertTrue(appUser.passwordHash.startsWith("\$2"))
  }

  @Test
  fun changePasswordUpdatesPasswordHashAndAllowsNewPassword() {
    val username = createTestUser(password = "old-password")
    val session = login(username = username, password = "old-password")
    val oldPasswordHash =
      appUserRepository.findByUsername(username)?.passwordHash ?: error("User is not found")

    mockMvc
      .perform(
        put("/api/me/password")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            changePasswordJson(
              currentPassword = "old-password",
              newPassword = "new-password",
              newPasswordConfirm = "new-password",
            )
          )
      )
      .andExpect(status().isNoContent)

    val changedUser =
      appUserRepository.findByUsername(username) ?: error("Changed user is not found")
    assertNotEquals(oldPasswordHash, changedUser.passwordHash)
    assertTrue(changedUser.passwordHash.startsWith("\$2"))

    mockMvc
      .perform(
        post("/api/login")
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(loginJson(username = username, password = "old-password"))
      )
      .andExpect(status().isUnauthorized)

    login(username = username, password = "new-password")
  }

  @Test
  fun changePasswordRejectsWrongCurrentPassword() {
    val username = createTestUser(password = "old-password")
    val session = login(username = username, password = "old-password")

    mockMvc
      .perform(
        put("/api/me/password")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            changePasswordJson(
              currentPassword = "wrong-password",
              newPassword = "new-password",
              newPasswordConfirm = "new-password",
            )
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
      .andExpect(jsonPath("$.errors[0].message").value("現在のパスワードが正しくありません"))
  }

  @Test
  fun changePasswordRejectsMismatchedConfirmation() {
    val username = createTestUser(password = "old-password")
    val session = login(username = username, password = "old-password")

    mockMvc
      .perform(
        put("/api/me/password")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            changePasswordJson(
              currentPassword = "old-password",
              newPassword = "new-password",
              newPasswordConfirm = "mismatched-password",
            )
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.errors[0].field").value("newPasswordConfirm"))
      .andExpect(jsonPath("$.errors[0].message").value("新しいパスワードと確認用パスワードが一致しません"))
  }

  @Test
  fun changePasswordRejectsTooShortNewPassword() {
    val username = createTestUser(password = "old-password")
    val session = login(username = username, password = "old-password")

    mockMvc
      .perform(
        put("/api/me/password")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            changePasswordJson(
              currentPassword = "old-password",
              newPassword = "short",
              newPasswordConfirm = "short",
            )
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.message").value("入力内容に誤りがあります"))
      .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
      .andExpect(jsonPath("$.errors[0].message").value("新しいパスワードは12文字以上200文字以内で入力してください"))
  }

  @Test
  fun changePasswordRejectsMissingCsrfToken() {
    val username = createTestUser(password = "old-password")
    val session = login(username = username, password = "old-password")

    mockMvc
      .perform(
        put("/api/me/password")
          .session(session)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            changePasswordJson(
              currentPassword = "old-password",
              newPassword = "new-password",
              newPasswordConfirm = "new-password",
            )
          )
      )
      .andExpect(status().isForbidden)
  }

  private fun login(
    username: String = TEST_USERNAME,
    password: String = TEST_PASSWORD,
  ): MockHttpSession {
    val result =
      mockMvc
        .perform(
          post("/api/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(username = username, password = password))
        )
        .andExpect(status().isOk)
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.mfaRequired").value(false))
        .andExpect(jsonPath("$.user.username").value(username))
        .andReturn()

    return result.request.session as MockHttpSession
  }

  private fun loginJson(
    username: String = TEST_USERNAME,
    password: String = TEST_PASSWORD,
  ): String =
    """
    {
      "username": "$username",
      "password": "$password"
    }
    """
      .trimIndent()

  private fun changePasswordJson(
    currentPassword: String,
    newPassword: String,
    newPasswordConfirm: String,
  ): String =
    """
    {
      "currentPassword": "$currentPassword",
      "newPassword": "$newPassword",
      "newPasswordConfirm": "$newPasswordConfirm"
    }
    """
      .trimIndent()

  private fun createTestUser(password: String): String {
    val username = "password-change-${UUID.randomUUID()}"
    appUserRepository.save(
      AppUserEntity(
        username = username,
        passwordHash = passwordEncoder.encode(password) ?: error("Password hash is empty"),
      )
    )
    return username
  }

  companion object {
    private const val TEST_USERNAME = "test-user"
    private const val TEST_PASSWORD = "test-password"
  }
}
