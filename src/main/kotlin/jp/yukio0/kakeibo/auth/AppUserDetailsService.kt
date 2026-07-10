package jp.yukio0.kakeibo.auth

import jp.yukio0.kakeibo.user.AppUserRepository
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AppUserDetailsService(private val appUserRepository: AppUserRepository) : UserDetailsService {

  @Transactional(readOnly = true)
  override fun loadUserByUsername(username: String): UserDetails {
    val appUser =
      appUserRepository.findByUsername(username.trim())
        ?: throw UsernameNotFoundException("ユーザーが見つかりません")

    return User.withUsername(appUser.username).password(appUser.passwordHash).roles("USER").build()
  }
}
