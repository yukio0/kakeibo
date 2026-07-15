package jp.yukio0.kakeibo.budget

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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BudgetApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var objectMapper: ObjectMapper

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
  fun monthlyBudgetWithoutSettingsReturnsNullBudgetsAndEveryExpenseCategory() {
    val expenseCategory = saveCategory("Budget unset expense", TransactionType.EXPENSE)
    val incomeCategory = saveCategory("Budget unset income", TransactionType.INCOME)

    val response = getMonthlyBudget(year = 2091, month = 1)

    assertEquals(2091, response.path("year").asInt())
    assertEquals(1, response.path("month").asInt())
    assertTrue(response.path("overallBudget").isNull)
    assertEquals(0L, response.path("spentAmount").asLong())
    assertTrue(response.path("remainingAmount").isNull)
    assertTrue(response.path("overAmount").isNull)

    val categories = response.path("categories")
    assertEquals(categoryRepository.countByType(TransactionType.EXPENSE).toInt(), categories.size())
    assertTrue(categories.any { it.path("categoryId").asLong() == expenseCategory.id })
    assertFalse(categories.any { it.path("categoryId").asLong() == incomeCategory.id })

    val expenseBudget = categoryBudget(response, requireId(expenseCategory))
    assertTrue(expenseBudget.path("budgetAmount").isNull)
    assertEquals(0L, expenseBudget.path("spentAmount").asLong())
    assertTrue(expenseBudget.path("remainingAmount").isNull)
    assertTrue(expenseBudget.path("overAmount").isNull)
  }

  @Test
  fun putThenGetReturnsOverallAndCategoryBudgetsWithActualRemainingAndOverAmounts() {
    val foodCategory = saveCategory("Budget food", TransactionType.EXPENSE)
    val hobbyCategory = saveCategory("Budget hobby", TransactionType.EXPENSE)
    val paymentMethod = savePaymentMethod("Budget cash")
    saveTransaction(
      category = foodCategory,
      paymentMethod = paymentMethod,
      type = TransactionType.EXPENSE,
      amount = 7_000,
      transactionDate = LocalDate.of(2091, 2, 1),
    )
    saveTransaction(
      category = hobbyCategory,
      paymentMethod = paymentMethod,
      type = TransactionType.EXPENSE,
      amount = 12_000,
      transactionDate = LocalDate.of(2091, 2, 28),
    )

    val request =
      """
      {
        "year": 2091,
        "month": 2,
        "overallBudget": 15000,
        "categoryBudgets": [
          {"categoryId": ${requireId(foodCategory)}, "amount": 10000},
          {"categoryId": ${requireId(hobbyCategory)}, "amount": 10000}
        ]
      }
      """
        .trimIndent()

    assertCalculatedBudget(putMonthlyBudget(request), foodCategory, hobbyCategory)
    assertCalculatedBudget(getMonthlyBudget(year = 2091, month = 2), foodCategory, hobbyCategory)
  }

  @Test
  fun monthlyBudgetExcludesIncomeTransferAndOtherMonthsFromActualAmounts() {
    val expenseCategory = saveCategory("Budget target expense", TransactionType.EXPENSE)
    val incomeCategory = saveCategory("Budget excluded income", TransactionType.INCOME)
    val paymentMethod = savePaymentMethod("Budget card")
    saveTransaction(
      category = expenseCategory,
      paymentMethod = paymentMethod,
      type = TransactionType.EXPENSE,
      amount = 100,
      transactionDate = LocalDate.of(2091, 3, 1),
    )
    saveTransaction(
      category = incomeCategory,
      type = TransactionType.INCOME,
      amount = 9_999,
      transactionDate = LocalDate.of(2091, 3, 2),
    )
    saveTransferTransaction(amount = 8_888, transactionDate = LocalDate.of(2091, 3, 3))
    saveTransaction(
      category = expenseCategory,
      paymentMethod = paymentMethod,
      type = TransactionType.EXPENSE,
      amount = 7_777,
      transactionDate = LocalDate.of(2091, 4, 1),
    )

    val response = getMonthlyBudget(year = 2091, month = 3)

    assertEquals(100L, response.path("spentAmount").asLong())
    assertEquals(
      100L,
      categoryBudget(response, requireId(expenseCategory)).path("spentAmount").asLong(),
    )
    assertFalse(
      response.path("categories").any { it.path("categoryId").asLong() == incomeCategory.id }
    )
  }

  @Test
  fun putReplacesAllSettingsAndNullOrEmptyValuesClearBudgets() {
    val foodCategory = saveCategory("Budget replace food", TransactionType.EXPENSE)
    val hobbyCategory = saveCategory("Budget replace hobby", TransactionType.EXPENSE)
    val foodCategoryId = requireId(foodCategory)
    val hobbyCategoryId = requireId(hobbyCategory)
    putMonthlyBudget(
      """
      {
        "year": 2091,
        "month": 4,
        "overallBudget": 50000,
        "categoryBudgets": [
          {"categoryId": $foodCategoryId, "amount": 10000},
          {"categoryId": $hobbyCategoryId, "amount": 20000}
        ]
      }
      """
        .trimIndent()
    )

    putMonthlyBudget(
      """
      {
        "year": 2091,
        "month": 4,
        "overallBudget": null,
        "categoryBudgets": [
          {"categoryId": $hobbyCategoryId, "amount": 30000}
        ]
      }
      """
        .trimIndent()
    )

    var response = getMonthlyBudget(year = 2091, month = 4)
    assertTrue(response.path("overallBudget").isNull)
    assertTrue(categoryBudget(response, foodCategoryId).path("budgetAmount").isNull)
    assertEquals(30_000L, categoryBudget(response, hobbyCategoryId).path("budgetAmount").asLong())

    response =
      putMonthlyBudget(
        """
        {
          "year": 2091,
          "month": 4,
          "overallBudget": null,
          "categoryBudgets": []
        }
        """
          .trimIndent()
      )
    assertTrue(categoryBudget(response, hobbyCategoryId).path("budgetAmount").isNull)
  }

  @Test
  fun invalidYearOrMonthReturnsBadRequest() {
    mockMvc
      .perform(get("/api/budgets/monthly").param("year", "0").param("month", "1"))
      .andExpect(status().isBadRequest)

    mockMvc
      .perform(get("/api/budgets/monthly").param("year", "2091").param("month", "13"))
      .andExpect(status().isBadRequest)

    expectPutBadRequest(
      """
      {
        "year": 10000,
        "month": 1,
        "overallBudget": null,
        "categoryBudgets": []
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun zeroNegativeAndGreaterThanIntBudgetAmountsReturnBadRequest() {
    val expenseCategory = saveCategory("Budget amount validation", TransactionType.EXPENSE)
    val categoryId = requireId(expenseCategory)

    expectPutBadRequest(
      """
      {
        "year": 2091,
        "month": 5,
        "overallBudget": 0,
        "categoryBudgets": []
      }
      """
        .trimIndent()
    )
    expectPutBadRequest(
      """
      {
        "year": 2091,
        "month": 5,
        "overallBudget": null,
        "categoryBudgets": [{"categoryId": $categoryId, "amount": -1}]
      }
      """
        .trimIndent()
    )
    expectPutBadRequest(
      """
      {
        "year": 2091,
        "month": 5,
        "overallBudget": 2147483648,
        "categoryBudgets": []
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun duplicatedCategoryBudgetReturnsBadRequest() {
    val expenseCategory = saveCategory("Budget duplicate", TransactionType.EXPENSE)
    val categoryId = requireId(expenseCategory)

    expectPutBadRequest(
      """
      {
        "year": 2091,
        "month": 6,
        "overallBudget": null,
        "categoryBudgets": [
          {"categoryId": $categoryId, "amount": 1000},
          {"categoryId": $categoryId, "amount": 2000}
        ]
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun incomeCategoryBudgetReturnsBadRequest() {
    val incomeCategory = saveCategory("Budget invalid income", TransactionType.INCOME)

    expectPutBadRequest(
      """
      {
        "year": 2091,
        "month": 7,
        "overallBudget": null,
        "categoryBudgets": [
          {"categoryId": ${requireId(incomeCategory)}, "amount": 1000}
        ]
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun missingCategoryBudgetReturnsBadRequest() {
    expectPutBadRequest(
      """
      {
        "year": 2091,
        "month": 8,
        "overallBudget": null,
        "categoryBudgets": [
          {"categoryId": ${Long.MAX_VALUE}, "amount": 1000}
        ]
      }
      """
        .trimIndent()
    )
  }

  private fun getMonthlyBudget(year: Int, month: Int): JsonNode {
    val result =
      mockMvc
        .perform(
          get("/api/budgets/monthly")
            .param("year", year.toString())
            .param("month", month.toString())
        )
        .andExpect(status().isOk)
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andReturn()
    return objectMapper.readTree(result.response.contentAsString)
  }

  private fun putMonthlyBudget(request: String): JsonNode {
    val result =
      mockMvc
        .perform(
          put("/api/budgets/monthly").contentType(MediaType.APPLICATION_JSON).content(request)
        )
        .andExpect(status().isOk)
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andReturn()
    return objectMapper.readTree(result.response.contentAsString)
  }

  private fun expectPutBadRequest(request: String) {
    mockMvc
      .perform(put("/api/budgets/monthly").contentType(MediaType.APPLICATION_JSON).content(request))
      .andExpect(status().isBadRequest)
  }

  private fun assertCalculatedBudget(
    response: JsonNode,
    foodCategory: CategoryEntity,
    hobbyCategory: CategoryEntity,
  ) {
    assertEquals(15_000L, response.path("overallBudget").asLong())
    assertEquals(19_000L, response.path("spentAmount").asLong())
    assertEquals(0L, response.path("remainingAmount").asLong())
    assertEquals(4_000L, response.path("overAmount").asLong())

    val foodBudget = categoryBudget(response, requireId(foodCategory))
    assertEquals("Budget food", foodBudget.path("categoryName").textValue())
    assertEquals(10_000L, foodBudget.path("budgetAmount").asLong())
    assertEquals(7_000L, foodBudget.path("spentAmount").asLong())
    assertEquals(3_000L, foodBudget.path("remainingAmount").asLong())
    assertEquals(0L, foodBudget.path("overAmount").asLong())

    val hobbyBudget = categoryBudget(response, requireId(hobbyCategory))
    assertEquals(10_000L, hobbyBudget.path("budgetAmount").asLong())
    assertEquals(12_000L, hobbyBudget.path("spentAmount").asLong())
    assertEquals(0L, hobbyBudget.path("remainingAmount").asLong())
    assertEquals(2_000L, hobbyBudget.path("overAmount").asLong())
  }

  private fun categoryBudget(response: JsonNode, categoryId: Long): JsonNode =
    response.path("categories").firstOrNull { it.path("categoryId").asLong() == categoryId }
      ?: error("Category budget is missing: $categoryId")

  private fun saveCategory(name: String, type: TransactionType): CategoryEntity =
    categoryRepository.saveAndFlush(CategoryEntity(name = name, type = type, displayOrder = 990))

  private fun savePaymentMethod(name: String): PaymentMethodEntity =
    paymentMethodRepository.saveAndFlush(PaymentMethodEntity(name = name, displayOrder = 990))

  private fun saveTransaction(
    category: CategoryEntity,
    type: TransactionType,
    amount: Int,
    transactionDate: LocalDate,
    paymentMethod: PaymentMethodEntity? = null,
  ): TransactionEntity =
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = category,
        paymentMethod = paymentMethod,
        type = type,
        transactionDate = transactionDate,
        amount = amount,
        memo = "Budget API test",
        displayOrder = 1,
      )
    )

  private fun saveTransferTransaction(
    amount: Int,
    transactionDate: LocalDate,
  ): TransactionEntity {
    val source =
      transferAccountRepository.saveAndFlush(
        TransferAccountEntity(name = "Budget source", displayOrder = 990)
      )
    val destination =
      transferAccountRepository.saveAndFlush(
        TransferAccountEntity(name = "Budget destination", displayOrder = 991)
      )
    return transactionRepository.saveAndFlush(
      TransactionEntity(
        transferSource = source,
        transferDestination = destination,
        type = TransactionType.TRANSFER,
        transactionDate = transactionDate,
        amount = amount,
        memo = "Budget transfer test",
        displayOrder = 1,
      )
    )
  }

  private fun requireId(category: CategoryEntity): Long =
    category.id ?: error("Category id is not assigned")
}
