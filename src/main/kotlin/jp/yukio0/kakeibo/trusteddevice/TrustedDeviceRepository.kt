package jp.yukio0.kakeibo.trusteddevice

import jp.yukio0.kakeibo.user.AppUserEntity
import org.springframework.data.jpa.repository.JpaRepository

interface TrustedDeviceRepository : JpaRepository<TrustedDeviceEntity, Long> {
  fun findByTokenHashAndAppUser(
    tokenHash: String,
    appUser: AppUserEntity,
  ): TrustedDeviceEntity?

  fun findAllByAppUserOrderByCreatedAtDesc(appUser: AppUserEntity): List<TrustedDeviceEntity>

  fun deleteByTokenHashAndAppUser(
    tokenHash: String,
    appUser: AppUserEntity,
  )

  fun deleteAllByAppUser(appUser: AppUserEntity)
}
