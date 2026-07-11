package jp.yukio0.kakeibo.auth

import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

class InitialUserInitializerTests {

  private val repository = mock(AppUserRepository::class.java)
  private val passwordEncoder = BCryptPasswordEncoder()

  private fun initializer(username: String, password: String) =
    InitialUserInitializer(repository, passwordEncoder, username, password)

  @Test
  fun skipsWhenBothUsernameAndPasswordAreEmpty() {
    initializer(username = "", password = "").run(DefaultApplicationArguments())

    verify(repository, never()).save(any())
  }

  @Test
  fun failsWhenOnlyPasswordIsProvided() {
    assertFailsWith<IllegalArgumentException> {
      initializer(username = "  ", password = "bootstrap-pw-123").run(DefaultApplicationArguments())
    }
  }

  @Test
  fun failsWhenOnlyUsernameIsProvided() {
    assertFailsWith<IllegalArgumentException> {
      initializer(username = "boot-user", password = "").run(DefaultApplicationArguments())
    }
  }

  @Test
  fun failsWhenPasswordIsTooShort() {
    assertFailsWith<IllegalArgumentException> {
      initializer(username = "boot-user", password = "short-pw").run(DefaultApplicationArguments())
    }
  }

  @Test
  fun createsUserWhenConfigurationIsValid() {
    `when`(repository.findByUsername("boot-user")).thenReturn(null)

    initializer(username = " boot-user ", password = "bootstrap-pw-123")
      .run(DefaultApplicationArguments())

    verify(repository).save(any())
  }

  @Test
  fun doesNothingWhenUserAlreadyExists() {
    `when`(repository.findByUsername("boot-user"))
      .thenReturn(AppUserEntity(username = "boot-user", passwordHash = "hash"))

    initializer(username = "boot-user", password = "bootstrap-pw-123")
      .run(DefaultApplicationArguments())

    verify(repository, never()).save(any())
  }
}
