package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import org.hamcrest.Matchers.hasSize
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
class TransactionApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var categoryRepository: CategoryRepository

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
      .andExpect(jsonPath("$[0].amount").value(2000))
      .andExpect(jsonPath("$[0].memo").value("2番目に登録したが先に表示"))
      .andExpect(jsonPath("$[0].displayOrder").value(1))
      .andExpect(jsonPath("$[1].id").value(first.id!!.toInt()))
      .andExpect(jsonPath("$[1].date").value("2026-07-01"))
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
        type = type,
        transactionDate = transactionDate,
        amount = amount,
        memo = memo,
        displayOrder = displayOrder,
      )
    )
}
