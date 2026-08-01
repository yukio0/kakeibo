package jp.yukio0.kakeibo.auth

import jp.yukio0.kakeibo.user.AppUserEntity
import org.springframework.data.jpa.repository.JpaRepository

interface MfaRecoveryCodeRepository : JpaRepository<MfaRecoveryCodeEntity, Long> {
  fun findAllByAppUserAndUsedAtIsNullOrderByIdAsc(
    appUser: AppUserEntity
  ): List<MfaRecoveryCodeEntity>

  fun countByAppUser(appUser: AppUserEntity): Long

  fun countByAppUserAndUsedAtIsNull(appUser: AppUserEntity): Long

  fun deleteAllByAppUser(appUser: AppUserEntity)
}
