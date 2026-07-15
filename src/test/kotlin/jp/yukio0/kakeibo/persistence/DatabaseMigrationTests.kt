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
            'category_budgets',
            'monthly_budgets',
            'payment_methods',
            'recurring_transaction_registrations',
            'recurring_transaction_templates',
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

    assertEquals(10, tableCount)
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

  @Test
  fun databaseRejectsZeroOverallBudget() {
    assertFailsWith<DataIntegrityViolationException> {
      jdbcTemplate.update(
        """
        INSERT INTO monthly_budgets (budget_year, budget_month, overall_budget)
        VALUES (2090, 1, 0)
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun databaseRejectsDuplicatedMonthlyBudget() {
    jdbcTemplate.update(
      """
      INSERT INTO monthly_budgets (budget_year, budget_month, overall_budget)
      VALUES (2090, 2, 10000)
      """
        .trimIndent()
    )

    assertFailsWith<DataIntegrityViolationException> {
      jdbcTemplate.update(
        """
        INSERT INTO monthly_budgets (budget_year, budget_month, overall_budget)
        VALUES (2090, 2, 20000)
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun recurringTransactionMigrationCreatesExpectedConstraints() {
    val constraintCount =
      jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM information_schema.table_constraints
        WHERE LOWER(table_schema) = 'public'
          AND LOWER(constraint_name) IN (
            'ck_recurring_templates_day_of_month',
            'ck_recurring_templates_type',
            'ck_recurring_templates_default_amount',
            'ck_recurring_templates_display_order',
            'ck_recurring_templates_targets_by_type',
            'fk_recurring_templates_category',
            'fk_recurring_templates_payment_method',
            'fk_recurring_templates_transfer_source',
            'fk_recurring_templates_transfer_destination',
            'ck_recurring_registrations_year',
            'ck_recurring_registrations_month',
            'uq_recurring_registrations_template_month',
            'uq_recurring_registrations_transaction',
            'fk_recurring_registrations_template',
            'fk_recurring_registrations_transaction'
          )
        """
          .trimIndent(),
        Int::class.java,
      )

    assertEquals(15, constraintCount)
  }

  @Test
  fun databaseRejectsRecurringTemplateWithTargetsForWrongType() {
    val incomeCategoryId =
      jdbcTemplate.queryForObject(
        "SELECT id FROM categories WHERE type = 'INCOME' ORDER BY id LIMIT 1",
        Long::class.java,
      )
    val paymentMethodId =
      jdbcTemplate.queryForObject(
        "SELECT id FROM payment_methods ORDER BY id LIMIT 1",
        Long::class.java,
      )

    assertFailsWith<DataIntegrityViolationException> {
      jdbcTemplate.update(
        """
        INSERT INTO recurring_transaction_templates (
          name,
          enabled,
          day_of_month,
          type,
          category_id,
          payment_method_id,
          display_order
        ) VALUES (?, TRUE, 1, 'INCOME', ?, ?, 0)
        """
          .trimIndent(),
        "Invalid recurring income",
        incomeCategoryId,
        paymentMethodId,
      )
    }
  }
}
