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
    val password = initialPassword

    // 両方未設定なら初期ユーザーを作らない構成として何もしない
    if (username.isEmpty() && password.isEmpty()) {
      return
    }

    // 片方でも指定されている場合、条件を満たさない設定は例外を投げて起動を失敗させる。
    // ApplicationRunner の例外は起動失敗として伝播し、コンテナは異常終了する。
    require(username.isNotEmpty()) {
      "KAKEIBO_INITIAL_USERNAME を設定してください（KAKEIBO_INITIAL_PASSWORD のみ指定されています）"
    }
    require(password.isNotBlank()) { "KAKEIBO_INITIAL_PASSWORD を設定してください" }
    require(password.length >= MIN_PASSWORD_LENGTH) {
      "KAKEIBO_INITIAL_PASSWORD は ${MIN_PASSWORD_LENGTH} 文字以上で設定してください"
    }

    if (appUserRepository.findByUsername(username) != null) {
      return
    }

    val passwordHash = passwordEncoder.encode(password) ?: error("Password hash is empty")

    appUserRepository.save(
      AppUserEntity(
        username = username,
        passwordHash = passwordHash,
      )
    )
  }

  private companion object {
    // パスワード変更API(ChangePasswordRequest)の @Size(min = 12) と揃える
    const val MIN_PASSWORD_LENGTH = 12
  }
}
