package jp.yukio0.kakeibo.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import jp.yukio0.kakeibo.persistence.IdentifiableEntity
import jp.yukio0.kakeibo.user.AppUserEntity
import org.hibernate.annotations.CreationTimestamp

/** 2段階認証のリカバリーコード。平文は発行時に一度だけ返し、DBにはハッシュだけを残す。 */
@Entity
@Table(name = "mfa_recovery_codes")
class MfaRecoveryCodeEntity(
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "app_user_id", nullable = false)
  var appUser: AppUserEntity,
  @field:Column(name = "code_hash", nullable = false, length = 100) var codeHash: String,
  @field:Column(name = "used_at") var usedAt: Instant? = null,
) : IdentifiableEntity() {

  @field:CreationTimestamp
  @field:Column(name = "created_at", nullable = false, updatable = false)
  var createdAt: Instant? = null
    protected set
}
