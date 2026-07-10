package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionMonthlySaveApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var categoryRepository: CategoryRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  private lateinit var mockMvc: MockMvc

  @BeforeEach
  fun setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
  }

  @Test
  fun monthlySaveCreatesUpdatesAndDeletesTargetMonthRows() {
    val expenseCategory = saveCategory("一括保存API支出カテゴリ", TransactionType.EXPENSE)
    val incomeCategory = saveCategory("一括保存API収入カテゴリ", TransactionType.INCOME)
    val updateTarget =
      saveTransaction(
        category = expenseCategory,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 7, 1),
        amount = 1000,
        memo = "更新前",
        displayOrder = 1,
      )
    val deleteTarget =
      saveTransaction(
        category = expenseCategory,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 7, 2),
        amount = 2000,
        memo = "削除対象",
        displayOrder = 2,
      )
    val otherMonth =
      saveTransaction(
        category = expenseCategory,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 8, 1),
        amount = 3000,
        memo = "別月",
        displayOrder = 1,
      )

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": ${updateTarget.id},
                "date": "2026-07-03",
                "type": "INCOME",
                "categoryId": ${incomeCategory.id},
                "amount": 5000,
                "memo": "更新後",
                "displayOrder": 2
              },
              {
                "id": null,
                "date": "2026-07-04",
                "type": "EXPENSE",
                "categoryId": ${expenseCategory.id},
                "amount": 1200,
                "memo": null,
                "displayOrder": 1
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.status").value("ok"))

    mockMvc
      .perform(get("/api/transactions").param("year", "2026").param("month", "7"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$", hasSize<Int>(2)))
      .andExpect(jsonPath("$[0].date").value("2026-07-04"))
      .andExpect(jsonPath("$[0].type").value("EXPENSE"))
      .andExpect(jsonPath("$[0].categoryId").value(expenseCategory.id!!.toInt()))
      .andExpect(jsonPath("$[0].amount").value(1200))
      .andExpect(jsonPath("$[0].memo").doesNotExist())
      .andExpect(jsonPath("$[0].displayOrder").value(1))
      .andExpect(jsonPath("$[1].id").value(updateTarget.id!!.toInt()))
      .andExpect(jsonPath("$[1].date").value("2026-07-03"))
      .andExpect(jsonPath("$[1].type").value("INCOME"))
      .andExpect(jsonPath("$[1].categoryId").value(incomeCategory.id!!.toInt()))
      .andExpect(jsonPath("$[1].categoryName").value("一括保存API収入カテゴリ"))
      .andExpect(jsonPath("$[1].amount").value(5000))
      .andExpect(jsonPath("$[1].memo").value("更新後"))
      .andExpect(jsonPath("$[1].displayOrder").value(2))

    assertFalse(transactionRepository.existsById(deleteTarget.id!!))
    val unchangedOtherMonth = transactionRepository.findById(otherMonth.id!!).orElseThrow()
    assertEquals(LocalDate.of(2026, 8, 1), unchangedOtherMonth.transactionDate)
    assertEquals(3000, unchangedOtherMonth.amount)
  }

  @Test
  fun monthlySaveRollsBackWhenAnyRowIsInvalid() {
    val category = saveCategory("一括保存APIロールバックカテゴリ", TransactionType.EXPENSE)
    val updateTarget =
      saveTransaction(
        category = category,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 7, 1),
        amount = 1000,
        memo = "ロールバック前",
        displayOrder = 1,
      )

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": ${updateTarget.id},
                "date": "2026-07-01",
                "type": "EXPENSE",
                "categoryId": ${category.id},
                "amount": 9999,
                "memo": "変更されない",
                "displayOrder": 1
              },
              {
                "id": null,
                "date": "2026-08-01",
                "type": "EXPENSE",
                "categoryId": ${category.id},
                "amount": 500,
                "memo": "対象月外",
                "displayOrder": 2
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("URLの年月と日付が一致していません"))
      .andExpect(jsonPath("$.errors[0].field").value("[1].date"))

    val unchanged = transactionRepository.findById(updateTarget.id!!).orElseThrow()
    assertEquals(1000, unchanged.amount)
    assertEquals("ロールバック前", unchanged.memo)
    assertEquals(1, transactionRepository.count())
  }

  @Test
  fun monthlySaveRejectsUpdatingOtherMonthRow() {
    val category = saveCategory("一括保存API別月更新拒否カテゴリ", TransactionType.EXPENSE)
    val otherMonth =
      saveTransaction(
        category = category,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 8, 1),
        amount = 3000,
        memo = "別月更新拒否",
        displayOrder = 1,
      )

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": ${otherMonth.id},
                "date": "2026-07-01",
                "type": "EXPENSE",
                "categoryId": ${category.id},
                "amount": 9999,
                "memo": "不正更新",
                "displayOrder": 1
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("対象月以外の家計簿データは更新できません"))
      .andExpect(jsonPath("$.errors[0].field").value("[0].id"))

    val unchanged = transactionRepository.findById(otherMonth.id!!).orElseThrow()
    assertEquals(LocalDate.of(2026, 8, 1), unchanged.transactionDate)
    assertEquals(3000, unchanged.amount)
  }

  @Test
  fun monthlySaveRejectsCategoryTypeMismatchAndMissingId() {
    val expenseCategory = saveCategory("一括保存API種別不一致支出カテゴリ", TransactionType.EXPENSE)

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": null,
                "date": "2026-07-01",
                "type": "INCOME",
                "categoryId": ${expenseCategory.id},
                "amount": 1000,
                "memo": "種別不一致",
                "displayOrder": 1
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("種別に合うカテゴリを選択してください"))
      .andExpect(jsonPath("$.errors[0].field").value("[0].categoryId"))

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": 99999999,
                "date": "2026-07-01",
                "type": "EXPENSE",
                "categoryId": ${expenseCategory.id},
                "amount": 1000,
                "memo": "存在しないID",
                "displayOrder": 1
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isNotFound)
      .andExpect(jsonPath("$.message").value("家計簿データが見つかりません"))
  }

  @Test
  fun monthlySaveReturnsFieldErrorsForBeanValidationViolations() {
    val tooLongMemo = "あ".repeat(501)

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": -1,
                "date": "",
                "type": null,
                "categoryId": null,
                "amount": null,
                "memo": "$tooLongMemo",
                "displayOrder": -1
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("入力内容に誤りがあります"))
      .andExpect(
        jsonPath(
          "$.errors[*].field",
          hasItems(
            "[0].id",
            "[0].date",
            "[0].type",
            "[0].categoryId",
            "[0].amount",
            "[0].memo",
            "[0].displayOrder",
          ),
        )
      )
  }

  @Test
  fun monthlySaveReturnsFieldErrorsForDateFormatAndDuplicateId() {
    val category = saveCategory("一括保存APIフィールドエラーカテゴリ", TransactionType.EXPENSE)
    val transaction =
      saveTransaction(
        category = category,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 7, 1),
        amount = 1000,
        memo = "重複ID",
        displayOrder = 1,
      )

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": null,
                "date": "2026/07/01",
                "type": "EXPENSE",
                "categoryId": ${category.id},
                "amount": 1000,
                "memo": "日付形式不正",
                "displayOrder": 1
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("日付の形式が不正です"))
      .andExpect(jsonPath("$.errors[0].field").value("[0].date"))

    mockMvc
      .perform(
        put("/api/transactions/monthly")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            [
              {
                "id": ${transaction.id},
                "date": "2026-07-01",
                "type": "EXPENSE",
                "categoryId": ${category.id},
                "amount": 1000,
                "memo": "1件目",
                "displayOrder": 1
              },
              {
                "id": ${transaction.id},
                "date": "2026-07-02",
                "type": "EXPENSE",
                "categoryId": ${category.id},
                "amount": 2000,
                "memo": "2件目",
                "displayOrder": 2
              }
            ]
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("同じ家計簿データIDが重複しています"))
      .andExpect(jsonPath("$.errors[0].field").value("[1].id"))
  }

  private fun saveCategory(name: String, type: TransactionType): CategoryEntity =
    categoryRepository.saveAndFlush(
      CategoryEntity(
        name = name,
        type = type,
        displayOrder = 960,
      )
    )

  private fun saveTransaction(
    category: CategoryEntity,
    type: TransactionType,
    transactionDate: LocalDate,
    amount: Int,
    memo: String,
    displayOrder: Int,
  ): TransactionEntity =
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = category,
        type = type,
        transactionDate = transactionDate,
        amount = amount,
        memo = memo,
        displayOrder = displayOrder,
      )
    )
}
