package jp.yukio0.kakeibo.persistence

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DatabaseMigrationTests {

  @Autowired private lateinit var jdbcTemplate: JdbcTemplate

  @Test
  fun migrationsCreateExpectedTablesAndInitialCategories() {
    val tableCount =
      jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE LOWER(table_schema) = 'public'
          AND LOWER(table_name) IN (
            'app_user',
            'categories',
            'payment_methods',
            'transfer_accounts',
            'transactions',
            'trusted_devices'
          )
        """
          .trimIndent(),
        Int::class.java,
      )
    val categoryCount =
      jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Int::class.java)
    val paymentMethodCount =
      jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_methods", Int::class.java)
    val transferAccountCount =
      jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfer_accounts", Int::class.java)

    assertEquals(6, tableCount)
    assertEquals(13, categoryCount)
    assertEquals(4, paymentMethodCount)
    assertEquals(2, transferAccountCount)
  }

  @Test
  fun databaseRejectsInvalidCategoryType() {
    assertFailsWith<DataIntegrityViolationException> {
      jdbcTemplate.update(
        """
        INSERT INTO categories (name, type, display_order)
        VALUES ('不正カテゴリ', 'INVALID', 0)
        """
          .trimIndent()
      )
    }
  }
}
