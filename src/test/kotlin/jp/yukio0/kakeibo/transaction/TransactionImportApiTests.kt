package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionImportApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var categoryRepository: CategoryRepository

  @Autowired private lateinit var paymentMethodRepository: PaymentMethodRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  private lateinit var mockMvc: MockMvc

  @BeforeEach
  fun setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
  }

  @Test
  fun previewReturnsMonthPlanWithoutWriting() {
    saveExpense(amount = 1_000, transactionDate = LocalDate.of(2026, 5, 10))

    val csv =
      csv(
        HEADER,
        listOf("2026-05-01", "支出", "食費", "現金", "3000", "スーパー"),
        listOf("2026-05-02", "収入", "給与", "現金", "200000", ""),
      )

    perform(csv, commit = false)
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.committed").value(false))
      .andExpect(jsonPath("$.totalRows").value(2))
      .andExpect(jsonPath("$.errors.length()").value(0))
      .andExpect(jsonPath("$.months.length()").value(1))
      .andExpect(jsonPath("$.months[0].year").value(2026))
      .andExpect(jsonPath("$.months[0].month").value(5))
      .andExpect(jsonPath("$.months[0].replacedCount").value(1))
      .andExpect(jsonPath("$.months[0].importedCount").value(2))

    // プレビューは反映しない
    assert(countIn(2026, 5) == 1)
  }

  @Test
  fun commitOverwritesTheMonth() {
    saveExpense(amount = 1_000, transactionDate = LocalDate.of(2026, 6, 10))

    val csv =
      csv(
        HEADER,
        listOf("2026-06-01", "支出", "食費", "現金", "3000", "置換1"),
        listOf("2026-06-02", "支出", "交通費", "現金", "500", "置換2"),
      )

    perform(csv, commit = true)
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.committed").value(true))
      .andExpect(jsonPath("$.months[0].replacedCount").value(1))
      .andExpect(jsonPath("$.months[0].importedCount").value(2))

    val amounts = amountsIn(2026, 6)
    assert(amounts == listOf(3_000, 500)) { "既存を置換して2件になるはずが $amounts" }
  }

  @Test
  fun onlyMonthsInCsvAreReplaced() {
    saveExpense(amount = 1_000, transactionDate = LocalDate.of(2026, 7, 10))
    saveExpense(amount = 2_000, transactionDate = LocalDate.of(2026, 8, 10))

    val csv = csv(HEADER, listOf("2026-07-05", "支出", "食費", "現金", "9000", ""))

    perform(csv, commit = true)
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.committed").value(true))

    assert(amountsIn(2026, 7) == listOf(9_000))
    // CSVに無い8月は不変
    assert(amountsIn(2026, 8) == listOf(2_000))
  }

  @Test
  fun unknownCategoryReturnsErrorAndDoesNotWrite() {
    val csv = csv(HEADER, listOf("2026-09-01", "支出", "存在しないカテゴリ", "現金", "1000", ""))

    perform(csv, commit = true)
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.committed").value(false))
      .andExpect(jsonPath("$.errors.length()").value(1))
      .andExpect(jsonPath("$.errors[0].row").value(1))

    assert(countIn(2026, 9) == 0)
  }

  @Test
  fun invalidDateAndAmountReturnRowErrors() {
    val csv =
      csv(
        HEADER,
        listOf("2026/10/01", "支出", "食費", "現金", "1000", ""),
        listOf("2026-10-02", "支出", "食費", "現金", "0", ""),
      )

    perform(csv, commit = true)
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.committed").value(false))
      .andExpect(jsonPath("$.errors.length()").value(2))
      .andExpect(jsonPath("$.errors[0].row").value(1))
      .andExpect(jsonPath("$.errors[1].row").value(2))
  }

  @Test
  fun headerMismatchReturnsFileError() {
    val csv = csv(listOf("日付", "種別", "金額"), listOf("2026-11-01", "支出", "1000"))

    perform(csv, commit = true)
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.committed").value(false))
      .andExpect(jsonPath("$.errors.length()").value(1))
      .andExpect(jsonPath("$.errors[0].row").value(0))
  }

  private fun perform(csv: ByteArray, commit: Boolean) =
    mockMvc.perform(
      multipart("/api/transactions/import")
        .file(MockMultipartFile("file", "import.csv", "text/csv", csv))
        .param("commit", commit.toString())
    )

  private fun csv(vararg rows: List<String>): ByteArray {
    val builder = StringBuilder()
    rows.forEach { row ->
      builder.append(row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
      builder.append("\r\n")
    }
    return builder.toString().toByteArray(Charsets.UTF_8)
  }

  private fun saveExpense(amount: Int, transactionDate: LocalDate) {
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = expenseCategory("食費"),
        paymentMethod = paymentMethod("現金"),
        type = TransactionType.EXPENSE,
        transactionDate = transactionDate,
        amount = amount,
        memo = "既存データ",
        displayOrder = 1,
      )
    )
  }

  private fun countIn(year: Int, month: Int): Int {
    val period = MonthlyPeriod.from(year, month)
    return transactionRepository
      .countByTransactionDateGreaterThanEqualAndTransactionDateLessThan(
        period.startDate,
        period.endDateExclusive,
      )
      .toInt()
  }

  private fun amountsIn(year: Int, month: Int): List<Int> {
    val period = MonthlyPeriod.from(year, month)
    return transactionRepository
      .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
        period.startDate,
        period.endDateExclusive,
      )
      .map { it.amount }
  }

  private fun expenseCategory(name: String): CategoryEntity =
    categoryRepository.findByNameAndType(name, TransactionType.EXPENSE)
      ?: error("カテゴリが見つかりません: $name")

  private fun paymentMethod(name: String): PaymentMethodEntity =
    paymentMethodRepository.findByName(name) ?: error("支払い方法が見つかりません: $name")

  private companion object {
    val HEADER = listOf("日付", "種別", "カテゴリ・振替元", "支払い方法・振替先", "金額", "メモ")
  }
}
