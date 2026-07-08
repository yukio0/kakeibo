package jp.yukio0.kakeibo.trusteddevice

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.CreationTimestamp

@Entity
@Table(name = "trusted_devices")
class TrustedDeviceEntity(
  @field:Column(name = "token_hash", nullable = false, length = 128, unique = true)
  var tokenHash: String,
  @field:Column(name = "device_name", nullable = false, length = 255) var deviceName: String,
  @field:Column(name = "last_used_at") var lastUsedAt: Instant? = null,
  @field:Column(name = "expires_at", nullable = false) var expiresAt: Instant,
) {

  @field:Id
  @field:GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: Long? = null
    protected set

  @field:CreationTimestamp
  @field:Column(name = "created_at", nullable = false, updatable = false)
  var createdAt: Instant? = null
    protected set
}
