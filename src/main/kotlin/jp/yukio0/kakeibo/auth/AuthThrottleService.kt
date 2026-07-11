package jp.yukio0.kakeibo.auth

import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.servlet.http.HttpServletRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import jp.yukio0.kakeibo.api.TooManyRequestsException
import org.springframework.stereotype.Service

/**
 * ログイン・2段階認証のブルートフォース対策。キー(IP＋対象ユーザー名)ごとに失敗回数を数え、 上限に達したらロック期間中は 429 を返す。認証の成否を知る呼び出し側から使う。
 *
 * 状態はインメモリ(Caffeine)。単一インスタンス前提で、再起動時にリセットされる。 複数インスタンスで共有したい場合は Redis 等の分散ストアが必要。
 */
@Service
class AuthThrottleService(private val clock: Clock = Clock.systemUTC()) {

  private data class Attempt(val failures: Int, val lockedUntil: Instant?)

  private val attempts =
    Caffeine.newBuilder()
      .expireAfterWrite(ENTRY_TTL)
      .maximumSize(MAX_TRACKED_KEYS)
      .build<String, Attempt>()

  /** ロック中なら [TooManyRequestsException] を投げる。認証を試みる前に呼ぶ。 */
  fun checkAllowed(key: String) {
    val lockedUntil = attempts.getIfPresent(key)?.lockedUntil ?: return
    val now = clock.instant()
    if (lockedUntil.isAfter(now)) {
      val retryAfter = Duration.between(now, lockedUntil).seconds.coerceAtLeast(1)
      throw TooManyRequestsException(
        message = "試行回数が上限を超えました。しばらくしてからお試しください",
        retryAfterSeconds = retryAfter,
      )
    }
  }

  /** 認証失敗時に呼ぶ。上限到達でロック期間を設定する。 */
  fun recordFailure(key: String) {
    val current = attempts.getIfPresent(key)
    val now = clock.instant()

    // ロックが切れていれば失敗数をリセットして数え直す
    val baseFailures =
      if (current?.lockedUntil != null && !current.lockedUntil.isAfter(now)) 0
      else current?.failures ?: 0
    val failures = baseFailures + 1
    val lockedUntil = if (failures >= MAX_FAILURES) now.plus(LOCK_DURATION) else null

    attempts.put(key, Attempt(failures = failures, lockedUntil = lockedUntil))
  }

  /** 認証成功時に呼ぶ。カウンタをクリアする。 */
  fun reset(key: String) {
    attempts.invalidate(key)
  }

  companion object {
    const val MAX_FAILURES = 5
    val LOCK_DURATION: Duration = Duration.ofMinutes(15)
    private val ENTRY_TTL: Duration = Duration.ofMinutes(30)
    private const val MAX_TRACKED_KEYS = 10_000L

    /** IPと対象を組み合わせたスロットルキー。IPが取れない場合も一意になるよう空文字で連結する。 */
    fun key(request: HttpServletRequest, scope: String, subject: String): String =
      "$scope|${clientIp(request)}|$subject"

    private fun clientIp(request: HttpServletRequest): String = request.remoteAddr ?: ""
  }
}
