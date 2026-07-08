package jp.yukio0.kakeibo.trusteddevice

import org.springframework.data.jpa.repository.JpaRepository

interface TrustedDeviceRepository : JpaRepository<TrustedDeviceEntity, Long> {
  fun findByTokenHash(tokenHash: String): TrustedDeviceEntity?
}
