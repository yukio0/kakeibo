package jp.yukio0.kakeibo.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jp.yukio0.kakeibo.persistence.AuditableEntity

@Entity
@Table(name = "app_user")
class AppUserEntity(
  @field:Column(nullable = false, length = 100, unique = true) var username: String,
  @field:Column(name = "password_hash", nullable = false, length = 100) var passwordHash: String,
  @field:Column(name = "two_factor_enabled", nullable = false)
  var twoFactorEnabled: Boolean = false,
  @field:Column(name = "two_factor_secret", length = 512) var twoFactorSecret: String? = null,
) : AuditableEntity()
