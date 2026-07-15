package jp.yukio0.kakeibo.e2e

import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Profile("e2e")
@RestController
@RequestMapping("/api/e2e")
class E2eDataController(
  private val jdbcTemplate: JdbcTemplate,
  private val appUserRepository: AppUserRepository,
  private val passwordEncoder: PasswordEncoder,
) {

  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  fun reset() {
    jdbcTemplate.update("DELETE FROM trusted_devices")
    jdbcTemplate.update("DELETE FROM transactions")
    jdbcTemplate.update("DELETE FROM monthly_budgets")
    jdbcTemplate.update("DELETE FROM categories")
    jdbcTemplate.update("DELETE FROM payment_methods")
    jdbcTemplate.update("DELETE FROM transfer_accounts")
    jdbcTemplate.update("DELETE FROM app_user")

    insertCategory("食費", "EXPENSE", 10)
    insertCategory("給与", "INCOME", 10)
    insertPaymentMethod("現金", 10)
    insertPaymentMethod("カード", 20)
    insertTransferAccount("財布", 10)
    insertTransferAccount("銀行口座", 20)

    appUserRepository.save(
      AppUserEntity(
        username = USERNAME,
        passwordHash = passwordEncoder.encode(PASSWORD) ?: error("Password hash is empty"),
        twoFactorEnabled = true,
        twoFactorSecret = TOTP_SECRET,
      )
    )
  }

  private fun insertCategory(
    name: String,
    type: String,
    displayOrder: Int,
  ) {
    jdbcTemplate.update(
      "INSERT INTO categories (name, type, display_order) VALUES (?, ?, ?)",
      name,
      type,
      displayOrder,
    )
  }

  private fun insertPaymentMethod(name: String, displayOrder: Int) {
    jdbcTemplate.update(
      "INSERT INTO payment_methods (name, display_order) VALUES (?, ?)",
      name,
      displayOrder,
    )
  }

  private fun insertTransferAccount(name: String, displayOrder: Int) {
    jdbcTemplate.update(
      "INSERT INTO transfer_accounts (name, display_order) VALUES (?, ?)",
      name,
      displayOrder,
    )
  }

  companion object {
    const val USERNAME = "e2e-user"
    const val PASSWORD = "e2e-password"
    const val TOTP_SECRET = "JBSWY3DPEHPK3PXP"
  }
}
