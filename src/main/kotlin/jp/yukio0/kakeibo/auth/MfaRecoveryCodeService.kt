package jp.yukio0.kakeibo.auth

import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import jp.yukio0.kakeibo.user.AppUserEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 2段階認証のリカバリーコードを発行・検証する。
 *
 * 平文は発行時のレスポンスでしか返さない。DBにはBCryptハッシュだけを保存し、 1度使ったコードは `used_at` を立てて再利用できないようにする。
 */
@Service
class MfaRecoveryCodeService(
  private val mfaRecoveryCodeRepository: MfaRecoveryCodeRepository,
  private val passwordEncoder: PasswordEncoder,
  private val clock: Clock = Clock.systemUTC(),
) {

  private val secureRandom = SecureRandom()

  /** 既存のコードをすべて破棄し、新しいコードを発行する。戻り値は表示用に整形した平文。 */
  @Transactional
  fun issue(appUser: AppUserEntity): List<String> {
    mfaRecoveryCodeRepository.deleteAllByAppUser(appUser)

    return List(CODE_COUNT) { generateCode() }
      .map { code ->
        mfaRecoveryCodeRepository.save(
          MfaRecoveryCodeEntity(
            appUser = appUser,
            codeHash = passwordEncoder.encode(code) ?: error("Recovery code hash is empty"),
          )
        )
        format(code)
      }
  }

  /**
   * 入力値が未使用のリカバリーコードなら使用済みにして true を返す。
   *
   * ハイフンや空白、大文字小文字の違いは吸収する。該当しなければ false を返すだけで、 呼び出し側が失敗回数の記録などを行う。
   */
  @Transactional
  fun consume(appUser: AppUserEntity, code: String): Boolean {
    val normalizedCode = normalize(code)
    if (!CODE_PATTERN.matches(normalizedCode)) {
      return false
    }

    val recoveryCode =
      mfaRecoveryCodeRepository.findAllByAppUserAndUsedAtIsNullOrderByIdAsc(appUser).firstOrNull {
        passwordEncoder.matches(normalizedCode, it.codeHash)
      } ?: return false

    recoveryCode.usedAt = Instant.now(clock)
    mfaRecoveryCodeRepository.save(recoveryCode)
    return true
  }

  @Transactional(readOnly = true)
  fun status(appUser: AppUserEntity): MfaRecoveryCodeStatusResponse =
    MfaRecoveryCodeStatusResponse(
      total = mfaRecoveryCodeRepository.countByAppUser(appUser).toInt(),
      remaining = mfaRecoveryCodeRepository.countByAppUserAndUsedAtIsNull(appUser).toInt(),
    )

  @Transactional(readOnly = true)
  fun remaining(appUser: AppUserEntity): Int =
    mfaRecoveryCodeRepository.countByAppUserAndUsedAtIsNull(appUser).toInt()

  /** 2FA無効化時など、リカバリーコードごと破棄する。 */
  @Transactional
  fun deleteAll(appUser: AppUserEntity) {
    mfaRecoveryCodeRepository.deleteAllByAppUser(appUser)
  }

  /** 6桁のTOTPコードと取り違えないよう、リカバリーコードの形をしているかだけを判定する。 */
  fun looksLikeRecoveryCode(code: String): Boolean = CODE_PATTERN.matches(normalize(code))

  private fun generateCode(): String =
    (1..CODE_LENGTH)
      .map { ALPHABET[secureRandom.nextInt(ALPHABET.length)] }
      .joinToString(separator = "")

  /** 読み上げ・書き写しやすいように中央でハイフン区切りにする。 */
  private fun format(code: String): String =
    "${code.take(CODE_LENGTH / 2)}-${code.drop(CODE_LENGTH / 2)}"

  private fun normalize(code: String): String = code.uppercase().filter { it in ALPHABET }

  private companion object {
    // 見間違えやすい 0/O、1/I/L を除いた英数字だけを使う
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val CODE_COUNT = 10
    const val CODE_LENGTH = 10
    val CODE_PATTERN = Regex("[$ALPHABET]{$CODE_LENGTH}")
  }
}
