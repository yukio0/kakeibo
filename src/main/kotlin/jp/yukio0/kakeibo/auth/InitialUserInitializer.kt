package jp.yukio0.kakeibo.auth

import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class InitialUserInitializer(
  private val appUserRepository: AppUserRepository,
  private val passwordEncoder: PasswordEncoder,
  @Value("\${kakeibo.initial-user.username:}") private val initialUsername: String,
  @Value("\${kakeibo.initial-user.password:}") private val initialPassword: String,
) : ApplicationRunner {

  @Transactional
  override fun run(args: ApplicationArguments) {
    val username = initialUsername.trim()
    if (username.isEmpty() || initialPassword.isEmpty()) {
      return
    }

    if (appUserRepository.findByUsername(username) != null) {
      return
    }

    val passwordHash = passwordEncoder.encode(initialPassword) ?: error("Password hash is empty")

    appUserRepository.save(
      AppUserEntity(
        username = username,
        passwordHash = passwordHash,
      )
    )
  }
}
