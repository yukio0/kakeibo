package jp.yukio0.kakeibo.summary

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import jp.yukio0.kakeibo.transaction.TransactionEntity
import jp.yukio0.kakeibo.transaction.TransactionRepository
import jp.yukio0.kakeibo.transfer.TransferAccountEntity
import jp.yukio0.kakeibo.transfer.TransferAccountRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MonthlySummaryApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var categoryRepository: CategoryRepository

  @Autowired private lateinit var paymentMethodRepository: PaymentMethodRepository

  @Autowired private lateinit var transferAccountRepository: TransferAccountRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  private lateinit var mockMvc: MockMvc

  @BeforeEach
  fun setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
  }

  @Test
  fun monthlySummaryReturnsExpenseOnlyTotalsWithLongCalculation() {
    val category = saveCategory("集計API支出のみカテゴリ", TransactionType.EXPENSE)
    saveTransaction(
      category = category,
      type = TransactionType.EXPENSE,
      amount = Int.MAX_VALUE,
      transactionDate = LocalDate.of(2026, 7, 1),
    )
    saveTransaction(
      category = category,
      type = TransactionType.EXPENSE,
      amount = Int.MAX_VALUE,
      transactionDate = LocalDate.of(2026, 7, 31),
    )

    mockMvc
      .perform(get("/api/summary/monthly").param("year", "2026").param("month", "7"))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.year").value(2026))
      .andExpect(jsonPath("$.month").value(7))
      .andExpect(jsonPath("$.incomeTotal").value(0))
      .andExpect(jsonPath("$.expenseTotal").value(4_294_967_294L))
      .andExpect(jsonPath("$.balance").value(-4_294_967_294L))
  }

  @Test
  fun monthlySummaryReturnsIncomeOnlyTotals() {
    val category = saveCategory("集計API収入のみカテゴリ", TransactionType.INCOME)
    saveTransaction(
      category = category,
      type = TransactionType.INCOME,
      amount = 300_000,
      transactionDate = LocalDate.of(2026, 8, 15),
    )

    mockMvc
      .perform(get("/api/summary/monthly").param("year", "2026").param("month", "8"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.incomeTotal").value(300_000))
      .andExpect(jsonPath("$.expenseTotal").value(0))
      .andExpect(jsonPath("$.balance").value(300_000))
  }

  @Test
  fun monthlySummaryReturnsIncomeExpenseBalanceAndExcludesOtherMonths() {
    val expenseCategory = saveCategory("集計API両方支出カテゴリ", TransactionType.EXPENSE)
    val incomeCategory = saveCategory("集計API両方収入カテゴリ", TransactionType.INCOME)
    saveTransaction(
      category = expenseCategory,
      type = TransactionType.EXPENSE,
      amount = 12_000,
      transactionDate = LocalDate.of(2026, 9, 1),
    )
    saveTransaction(
      category = expenseCategory,
      type = TransactionType.EXPENSE,
      amount = 8_000,
      transactionDate = LocalDate.of(2026, 9, 30),
    )
    saveTransaction(
      category = incomeCategory,
      type = TransactionType.INCOME,
      amount = 50_000,
      transactionDate = LocalDate.of(2026, 9, 10),
    )
    saveTransaction(
      category = incomeCategory,
      type = TransactionType.INCOME,
      amount = 999_999,
      transactionDate = LocalDate.of(2026, 10, 1),
    )

    mockMvc
      .perform(get("/api/summary/monthly").param("year", "2026").param("month", "9"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.incomeTotal").value(50_000))
      .andExpect(jsonPath("$.expenseTotal").value(20_000))
      .andExpect(jsonPath("$.balance").value(30_000))
  }

  @Test
  fun monthlySummaryExcludesTransferRows() {
    val expenseCategory = saveCategory("集計API振替除外支出カテゴリ", TransactionType.EXPENSE)
    saveTransaction(
      category = expenseCategory,
      type = TransactionType.EXPENSE,
      amount = 10_000,
      transactionDate = LocalDate.of(2026, 11, 1),
    )
    saveTransferTransaction(
      amount = 200_000,
      transactionDate = LocalDate.of(2026, 11, 2),
    )

    mockMvc
      .perform(get("/api/summary/monthly").param("year", "2026").param("month", "11"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.incomeTotal").value(0))
      .andExpect(jsonPath("$.expenseTotal").value(10_000))
      .andExpect(jsonPath("$.balance").value(-10_000))
  }

  @Test
  fun monthlySummaryReturnsZeroTotalsWhenNoData() {
    mockMvc
      .perform(get("/api/summary/monthly").param("year", "2099").param("month", "12"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.year").value(2099))
      .andExpect(jsonPath("$.month").value(12))
      .andExpect(jsonPath("$.incomeTotal").value(0))
      .andExpect(jsonPath("$.expenseTotal").value(0))
      .andExpect(jsonPath("$.balance").value(0))
  }

  @Test
  fun invalidYearMonthReturnsBadRequest() {
    mockMvc
      .perform(get("/api/summary/monthly").param("year", "2026").param("month", "13"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("年月が不正です"))

    mockMvc
      .perform(get("/api/summary/monthly").param("year", "2026"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("年月が不正です"))

    mockMvc
      .perform(get("/api/summary/monthly").param("year", "abc").param("month", "7"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("リクエストの形式が不正です"))
  }

  private fun saveCategory(name: String, type: TransactionType): CategoryEntity =
    categoryRepository.saveAndFlush(
      CategoryEntity(
        name = name,
        type = type,
        displayOrder = 950,
      )
    )

  private fun saveTransaction(
    category: CategoryEntity,
    type: TransactionType,
    amount: Int,
    transactionDate: LocalDate,
  ): TransactionEntity =
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = category,
        paymentMethod = defaultPaymentMethod(),
        type = type,
        transactionDate = transactionDate,
        amount = amount,
        memo = "集計APIテスト",
        displayOrder = 1,
      )
    )

  private fun saveTransferTransaction(
    amount: Int,
    transactionDate: LocalDate,
  ): TransactionEntity =
    transactionRepository.saveAndFlush(
      TransactionEntity(
        transferSource = defaultTransferSource(),
        transferDestination = defaultTransferDestination(),
        type = TransactionType.TRANSFER,
        transactionDate = transactionDate,
        amount = amount,
        memo = "振替集計除外テスト",
        displayOrder = 1,
      )
    )

  private fun defaultPaymentMethod(): PaymentMethodEntity =
    paymentMethodRepository.findByName("現金") ?: error("Payment method is not found")

  private fun defaultTransferSource(): TransferAccountEntity =
    transferAccountRepository.findByName("財布") ?: error("Transfer source is not found")

  private fun defaultTransferDestination(): TransferAccountEntity =
    transferAccountRepository.findByName("銀行口座") ?: error("Transfer destination is not found")
}
