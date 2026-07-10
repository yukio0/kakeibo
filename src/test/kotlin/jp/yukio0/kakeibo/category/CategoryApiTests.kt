package jp.yukio0.kakeibo.category

import java.time.LocalDate
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.transaction.TransactionEntity
import jp.yukio0.kakeibo.transaction.TransactionRepository
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CategoryApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var categoryRepository: CategoryRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  private lateinit var mockMvc: MockMvc

  @BeforeEach
  fun setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
  }

  @Test
  fun listCategoriesReturnsStableOrder() {
    mockMvc
      .perform(get("/api/categories"))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$[0].name").value("食費"))
      .andExpect(jsonPath("$[0].type").value("EXPENSE"))
      .andExpect(jsonPath("$[9].name").value("給与"))
      .andExpect(jsonPath("$[9].type").value("INCOME"))
  }

  @Test
  fun categoryEndpointsManageCategories() {
    mockMvc
      .perform(
        post("/api/categories")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": " APIテスト支出 ",
              "type": "EXPENSE",
              "displayOrder": 123
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isCreated)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.name").value("APIテスト支出"))
      .andExpect(jsonPath("$.type").value("EXPENSE"))
      .andExpect(jsonPath("$.displayOrder").value(123))

    val categoryId =
      categoryRepository.findByNameAndType("APIテスト支出", TransactionType.EXPENSE)?.id
        ?: error("Created category is not found")

    mockMvc
      .perform(get("/api/categories"))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$[*].name", hasItem("APIテスト支出")))

    mockMvc
      .perform(
        put("/api/categories/{id}", categoryId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "APIテスト収入",
              "type": "INCOME",
              "displayOrder": 55
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.id").value(categoryId.toInt()))
      .andExpect(jsonPath("$.name").value("APIテスト収入"))
      .andExpect(jsonPath("$.type").value("INCOME"))
      .andExpect(jsonPath("$.displayOrder").value(55))

    mockMvc.perform(delete("/api/categories/{id}", categoryId)).andExpect(status().isNoContent)

    assertFalse(categoryRepository.existsById(categoryId))
  }

  @Test
  fun invalidCategoryRequestsReturnBadRequest() {
    val tooLongName = "あ".repeat(101)

    mockMvc
      .perform(
        post("/api/categories")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": " ",
              "type": null,
              "displayOrder": -1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("入力内容に誤りがあります"))
      .andExpect(jsonPath("$.errors[*].field", hasItems("name", "type", "displayOrder")))

    mockMvc
      .perform(
        post("/api/categories")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "$tooLongName",
              "type": "EXPENSE",
              "displayOrder": 1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("入力内容に誤りがあります"))
      .andExpect(jsonPath("$.errors[*].field", hasItem("name")))

    mockMvc
      .perform(
        post("/api/categories")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "不正種別",
              "type": "INVALID",
              "displayOrder": 1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("リクエストの形式が不正です"))
  }

  @Test
  fun duplicatedCategoryNameInSameTypeReturnsBadRequest() {
    mockMvc
      .perform(
        post("/api/categories")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": " 食費 ",
              "type": "EXPENSE",
              "displayOrder": 999
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("同じ種別のカテゴリ名はすでに存在します"))
      .andExpect(jsonPath("$.errors[0].field").value("name"))
  }

  @Test
  fun missingCategoryIdReturnsNotFound() {
    val missingId = Long.MAX_VALUE

    mockMvc
      .perform(
        put("/api/categories/{id}", missingId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "存在しないカテゴリ",
              "type": "EXPENSE",
              "displayOrder": 1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isNotFound)
      .andExpect(jsonPath("$.message").value("カテゴリが見つかりません"))

    mockMvc
      .perform(delete("/api/categories/{id}", missingId))
      .andExpect(status().isNotFound)
      .andExpect(jsonPath("$.message").value("カテゴリが見つかりません"))
  }

  @Test
  fun usedCategoryCannotBeDeleted() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "削除不可テスト",
          type = TransactionType.EXPENSE,
          displayOrder = 901,
        )
      )
    val categoryId = category.id ?: error("Category id is not assigned")
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = category,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 7, 9),
        amount = 1000,
        memo = "カテゴリ削除不可テスト",
        displayOrder = 1,
      )
    )

    mockMvc
      .perform(delete("/api/categories/{id}", categoryId))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("使用中のカテゴリは削除できません"))

    assertTrue(categoryRepository.existsById(categoryId))
    assertTrue(transactionRepository.existsByCategoryId(categoryId))
  }
}
