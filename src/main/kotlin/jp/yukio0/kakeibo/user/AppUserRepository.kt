package jp.yukio0.kakeibo.user

import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUserEntity, Long> {
  fun findByUsername(username: String): AppUserEntity?
}
