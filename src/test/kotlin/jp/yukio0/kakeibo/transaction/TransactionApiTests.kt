package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionApiTests {

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
  fun getTransactionsReturnsOnlyRequestedMonthInStableOrder() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "取引APIテストカテゴリ",
          type = TransactionType.EXPENSE,
          displayOrder = 920,
        )
      )
    val second =
      saveTransaction(
        category = category,
        transactionDate = LocalDate.of(2026, 7, 2),
        amount = 2000,
        memo = "2番目に登録したが先に表示",
        displayOrder = 1,
      )
    val first =
      saveTransaction(
        category = category,
        transactionDate = LocalDate.of(2026, 7, 1),
        amount = 1000,
        memo = "1番目に登録したが後に表示",
        displayOrder = 2,
      )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 6, 30),
      amount = 3000,
      memo = "前月",
      displayOrder = 0,
    )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 8, 1),
      amount = 4000,
      memo = "翌月",
      displayOrder = 0,
    )

    mockMvc
      .perform(get("/api/transactions").param("year", "2026").param("month", "7"))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$", hasSize<Int>(2)))
      .andExpect(jsonPath("$[0].id").value(second.id!!.toInt()))
      .andExpect(jsonPath("$[0].date").value("2026-07-02"))
      .andExpect(jsonPath("$[0].type").value("EXPENSE"))
      .andExpect(jsonPath("$[0].categoryId").value(category.id!!.toInt()))
      .andExpect(jsonPath("$[0].categoryName").value("取引APIテストカテゴリ"))
      .andExpect(jsonPath("$[0].paymentMethodId").value(defaultPaymentMethod().id!!.toInt()))
      .andExpect(jsonPath("$[0].paymentMethodName").value("現金"))
      .andExpect(jsonPath("$[0].amount").value(2000))
      .andExpect(jsonPath("$[0].memo").value("2番目に登録したが先に表示"))
      .andExpect(jsonPath("$[0].displayOrder").value(1))
      .andExpect(jsonPath("$[1].id").value(first.id!!.toInt()))
      .andExpect(jsonPath("$[1].date").value("2026-07-01"))
  }

  @Test
  fun individualSaveEndpointsCreateUpdateAndDeleteOnlyTheTargetRow() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "個別保存APIカテゴリ",
          type = TransactionType.EXPENSE,
          displayOrder = 930,
        )
      )
    val existing =
      saveTransaction(
        category = category,
        transactionDate = LocalDate.of(2026, 7, 1),
        amount = 500,
        memo = "既存データ",
        displayOrder = 1,
      )

    mockMvc
      .perform(
        post("/api/transactions")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "date": "2026-07-02",
              "type": "EXPENSE",
              "categoryId": ${category.requiredId()},
              "paymentMethodId": ${defaultPaymentMethod().requiredId()},
              "amount": 1000,
              "memo": "新規データ"
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isCreated)
      .andExpect(jsonPath("$.date").value("2026-07-02"))
      .andExpect(jsonPath("$.amount").value(1000))

    val created = transactionRepository.findAll().single { it.memo == "新規データ" }

    mockMvc
      .perform(
        put("/api/transactions/${created.requiredId()}")
          .param("year", "2026")
          .param("month", "7")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "date": "2026-07-03",
              "type": "EXPENSE",
              "categoryId": ${category.requiredId()},
              "paymentMethodId": ${defaultPaymentMethod().requiredId()},
              "amount": 1200,
              "memo": "更新後"
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.id").value(created.requiredId().toInt()))
      .andExpect(jsonPath("$.amount").value(1200))

    val monthlyRows =
      transactionRepository
        .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
          LocalDate.of(2026, 7, 1),
          LocalDate.of(2026, 8, 1),
        )
    assertEquals(
      setOf(existing.requiredId(), created.requiredId()),
      monthlyRows.map { it.requiredId() }.toSet(),
    )
    assertEquals("既存データ", monthlyRows.single { it.requiredId() == existing.requiredId() }.memo)

    mockMvc
      .perform(
        delete("/api/transactions/${created.requiredId()}")
          .param("year", "2026")
          .param("month", "7")
      )
      .andExpect(status().isNoContent)

    mockMvc
      .perform(get("/api/transactions").param("year", "2026").param("month", "7"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$", hasSize<Int>(1)))
      .andExpect(jsonPath("$[0].id").value(existing.requiredId().toInt()))
  }

  @Test
  fun getTransactionsHandlesYearBoundary() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "年越しAPIテストカテゴリ",
          type = TransactionType.INCOME,
          displayOrder = 930,
        )
      )
    val december =
      saveTransaction(
        category = category,
        type = TransactionType.INCOME,
        transactionDate = LocalDate.of(2026, 12, 31),
        amount = 5000,
        memo = "年末",
        displayOrder = 1,
      )
    saveTransaction(
      category = category,
      type = TransactionType.INCOME,
      transactionDate = LocalDate.of(2027, 1, 1),
      amount = 6000,
      memo = "年始",
      displayOrder = 0,
    )

    mockMvc
      .perform(get("/api/transactions").param("year", "2026").param("month", "12"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$", hasSize<Int>(1)))
      .andExpect(jsonPath("$[0].id").value(december.id!!.toInt()))
      .andExpect(jsonPath("$[0].date").value("2026-12-31"))
      .andExpect(jsonPath("$[0].type").value("INCOME"))
  }

  @Test
  fun getTransactionsReturnsEmptyArrayWhenNoData() {
    mockMvc
      .perform(get("/api/transactions").param("year", "2099").param("month", "1"))
      .andExpect(status().isOk)
      .andExpect(content().json("[]"))
  }

  @Test
  fun exportTransactionsReturnsUtf8CsvWithoutBom() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "CSV出力カテゴリ",
          type = TransactionType.EXPENSE,
          displayOrder = 940,
        )
      )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 7, 31),
      amount = 1234,
      memo = "カンマ, \"引用\"\n改行",
      displayOrder = 1,
    )

    val csv =
      mockMvc
        .perform(get("/api/transactions/export"))
        .andExpect(status().isOk)
        .andExpect(content().contentType(MediaType("text", "csv", Charsets.UTF_8)))
        .andExpect(
          header().string("Content-Disposition", "attachment; filename=\"kakeibo-all.csv\"")
        )
        .andReturn()
        .response
        .contentAsByteArray

    assertEquals('"'.code.toByte(), csv.first())
    assertEquals(
      "\"日付\",\"種別\",\"カテゴリ・振替元\",\"支払い方法・振替先\",\"金額\",\"メモ\"\r\n" +
        "\"2026-07-31\",\"支出\",\"CSV出力カテゴリ\",\"現金\",\"1234\",\"カンマ, \"\"引用\"\"\n改行\"\r\n",
      String(csv, Charsets.UTF_8),
    )
  }

  @Test
  fun exportNeutralizesFormulaLikeCellsAgainstCsvInjection() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "=HYPERLINK(\"http://evil\")",
          type = TransactionType.EXPENSE,
          displayOrder = 941,
        )
      )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 7, 15),
      amount = 500,
      memo = "@SUM(A1)",
      displayOrder = 1,
    )

    val csv =
      mockMvc
        .perform(
          get("/api/transactions/export")
            .param("startDate", "2026-07-01")
            .param("endDate", "2026-07-31")
        )
        .andExpect(status().isOk)
        .andReturn()
        .response
        .contentAsByteArray

    // 先頭が =, @ のセルはアポストロフィで無効化され、" は "" のままエスケープされる
    assertEquals(
      "\"日付\",\"種別\",\"カテゴリ・振替元\",\"支払い方法・振替先\",\"金額\",\"メモ\"\r\n" +
        "\"2026-07-15\",\"支出\",\"'=HYPERLINK(\"\"http://evil\"\")\",\"現金\",\"500\",\"'@SUM(A1)\"\r\n",
      String(csv, Charsets.UTF_8),
    )
  }

  @Test
  fun exportTransactionsReturnsOnlySpecifiedPeriod() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "期間CSV出力カテゴリ",
          type = TransactionType.EXPENSE,
          displayOrder = 942,
        )
      )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 6, 30),
      amount = 100,
      memo = "期間前",
      displayOrder = 0,
    )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 7, 1),
      amount = 200,
      memo = "期間内の先頭",
      displayOrder = 1,
    )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 7, 31),
      amount = 300,
      memo = "期間内の末尾",
      displayOrder = 0,
    )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2026, 8, 1),
      amount = 400,
      memo = "期間後",
      displayOrder = 0,
    )

    val csv =
      mockMvc
        .perform(
          get("/api/transactions/export")
            .param("startDate", "2026-07-01")
            .param("endDate", "2026-07-31")
        )
        .andExpect(status().isOk)
        .andExpect(
          header()
            .string(
              "Content-Disposition",
              "attachment; filename=\"kakeibo-2026-07-01-2026-07-31.csv\"",
            )
        )
        .andReturn()
        .response
        .contentAsString

    assertTrue(csv.contains("期間内の先頭"))
    assertTrue(csv.contains("期間内の末尾"))
    assertFalse(csv.contains("期間前"))
    assertFalse(csv.contains("期間後"))
  }

  @Test
  fun exportTransactionsReturnsNoContentWhenThereIsNoData() {
    mockMvc
      .perform(get("/api/transactions/export"))
      .andExpect(status().isNoContent)
      .andExpect(content().string(""))

    mockMvc
      .perform(
        get("/api/transactions/export")
          .param("startDate", "2099-01-01")
          .param("endDate", "2099-12-31")
      )
      .andExpect(status().isNoContent)
      .andExpect(content().string(""))
  }

  @Test
  fun exportTransactionsRejectsIncompleteOrReversedPeriod() {
    mockMvc
      .perform(get("/api/transactions/export").param("startDate", "2026-07-01"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("開始日と終了日を両方指定してください"))

    mockMvc
      .perform(
        get("/api/transactions/export")
          .param("startDate", "2026-08-01")
          .param("endDate", "2026-07-31")
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("終了日は開始日以降を指定してください"))
  }

  @Test
  fun invalidYearMonthReturnsBadRequest() {
    mockMvc
      .perform(get("/api/transactions").param("year", "2026").param("month", "13"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("年月が不正です"))

    mockMvc
      .perform(get("/api/transactions").param("year", "0").param("month", "1"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("年月が不正です"))

    mockMvc
      .perform(get("/api/transactions").param("year", "2026"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("年月が不正です"))

    mockMvc
      .perform(get("/api/transactions").param("year", "2026").param("month", "abc"))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("リクエストの形式が不正です"))
  }

  private fun saveTransaction(
    category: CategoryEntity,
    type: TransactionType = TransactionType.EXPENSE,
    transactionDate: LocalDate,
    amount: Int,
    memo: String,
    displayOrder: Int,
  ): TransactionEntity =
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = category,
        paymentMethod = defaultPaymentMethod(),
        type = type,
        transactionDate = transactionDate,
        amount = amount,
        memo = memo,
        displayOrder = displayOrder,
      )
    )

  private fun defaultPaymentMethod(): PaymentMethodEntity =
    paymentMethodRepository.findByName("現金") ?: error("Payment method is not found")
}
