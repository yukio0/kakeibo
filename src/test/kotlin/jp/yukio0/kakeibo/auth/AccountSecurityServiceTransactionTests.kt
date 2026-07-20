package jp.yukio0.kakeibo.auth

import java.util.UUID
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceRepository
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
class AccountSecurityServiceTransactionTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var appUserRepository: AppUserRepository

  @Autowired private lateinit var passwordEncoder: PasswordEncoder

  @MockitoBean private lateinit var trustedDeviceRepository: TrustedDeviceRepository

  private val mockMvc: MockMvc by lazy {
    MockMvcBuilders.webAppContextSetup(context)
      .apply<DefaultMockMvcBuilder>(springSecurity())
      .build()
  }

  @Test
  fun changePasswordRollsBackAndKeepsCookieWhenTrustedDeviceRevocationFails() {
    val username = "password-rollback-${UUID.randomUUID()}"
    appUserRepository.save(
      AppUserEntity(
        username = username,
        passwordHash = passwordEncoder.encode(OLD_PASSWORD) ?: error("Password hash is empty"),
      )
    )
    val session = login(username)
    doThrow(IllegalStateException("forced trusted-device deletion failure"))
      .`when`(trustedDeviceRepository)
      .deleteAllByAppUser(anyAppUser())

    mockMvc
      .perform(
        put("/api/me/password")
          .session(session)
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "currentPassword": "$OLD_PASSWORD",
              "newPassword": "$NEW_PASSWORD",
              "newPasswordConfirm": "$NEW_PASSWORD"
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isInternalServerError)
      .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))

    val unchangedUser =
      appUserRepository.findByUsername(username) ?: error("Test user is not found")
    assertTrue(passwordEncoder.matches(OLD_PASSWORD, unchangedUser.passwordHash))
    assertFalse(passwordEncoder.matches(NEW_PASSWORD, unchangedUser.passwordHash))
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
                "password": "$OLD_PASSWORD"
              }
              """
                .trimIndent()
            )
        )
        .andExpect(status().isOk)
        .andReturn()

    return result.request.session as MockHttpSession
  }

  /** MockitoのJava APIが返すnullを、Kotlinの非null引数へ安全に渡すための代替値。 */
  private fun anyAppUser(): AppUserEntity =
    any(AppUserEntity::class.java)
      ?: AppUserEntity(username = "matcher-placeholder", passwordHash = "matcher-placeholder")

  private companion object {
    const val OLD_PASSWORD = "old-password"
    const val NEW_PASSWORD = "new-password"
  }
}
