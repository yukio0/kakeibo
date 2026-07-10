package jp.yukio0.kakeibo.trusteddevice

import java.time.Instant

data class TrustedDeviceResponse(
  val id: Long,
  val deviceName: String,
  val lastUsedAt: Instant?,
  val expiresAt: Instant,
  val createdAt: Instant?,
  val current: Boolean,
)
