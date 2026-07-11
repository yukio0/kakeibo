package jp.yukio0.kakeibo.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import jp.yukio0.kakeibo.api.TooManyRequestsException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class AuthThrottleServiceTests {

  private class MutableClock(var instant: Instant) : Clock() {
    override fun instant(): Instant = instant

    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this
  }

  @Test
  fun locksAfterReachingMaxFailures() {
    val clock = MutableClock(Instant.parse("2026-07-11T00:00:00Z"))
    val service = AuthThrottleService(clock)
    val key = "login|127.0.0.1|user"

    repeat(AuthThrottleService.MAX_FAILURES - 1) {
      service.recordFailure(key)
      assertDoesNotThrow { service.checkAllowed(key) }
    }

    // 上限到達でロック
    service.recordFailure(key)
    val exception = assertFailsWith<TooManyRequestsException> { service.checkAllowed(key) }
    assertEquals(AuthThrottleService.LOCK_DURATION.seconds, exception.retryAfterSeconds)
  }

  @Test
  fun unlocksAfterLockDurationElapses() {
    val clock = MutableClock(Instant.parse("2026-07-11T00:00:00Z"))
    val service = AuthThrottleService(clock)
    val key = "mfa|127.0.0.1|user"

    repeat(AuthThrottleService.MAX_FAILURES) { service.recordFailure(key) }
    assertFailsWith<TooManyRequestsException> { service.checkAllowed(key) }

    clock.instant = clock.instant.plus(AuthThrottleService.LOCK_DURATION).plusSeconds(1)
    assertDoesNotThrow { service.checkAllowed(key) }
  }

  @Test
  fun resetClearsFailureCount() {
    val clock = MutableClock(Instant.parse("2026-07-11T00:00:00Z"))
    val service = AuthThrottleService(clock)
    val key = "login|127.0.0.1|user"

    repeat(AuthThrottleService.MAX_FAILURES - 1) { service.recordFailure(key) }
    service.reset(key)

    // リセット後は再び最大回数まで許容される
    repeat(AuthThrottleService.MAX_FAILURES - 1) {
      service.recordFailure(key)
      assertDoesNotThrow { service.checkAllowed(key) }
    }
  }

  @Test
  fun retryAfterShrinksAsTimePasses() {
    val clock = MutableClock(Instant.parse("2026-07-11T00:00:00Z"))
    val service = AuthThrottleService(clock)
    val key = "login|127.0.0.1|user"

    repeat(AuthThrottleService.MAX_FAILURES) { service.recordFailure(key) }

    clock.instant = clock.instant.plus(Duration.ofMinutes(5))
    val exception = assertFailsWith<TooManyRequestsException> { service.checkAllowed(key) }
    assertEquals(
      AuthThrottleService.LOCK_DURATION.minusMinutes(5).seconds,
      exception.retryAfterSeconds,
    )
  }
}
