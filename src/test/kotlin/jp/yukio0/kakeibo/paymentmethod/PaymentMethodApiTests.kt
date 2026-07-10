package jp.yukio0.kakeibo.paymentmethod

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.transaction.TransactionEntity
import jp.yukio0.kakeibo.transaction.TransactionRepository
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
class PaymentMethodApiTests {

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
  fun getPaymentMethodsReturnsInitialItemsInDisplayOrder() {
    mockMvc
      .perform(get("/api/payment-methods"))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$[0].name").value("現金"))
      .andExpect(jsonPath("$[0].displayOrder").value(10))
      .andExpect(jsonPath("$[1].name").value("カード"))
      .andExpect(jsonPath("$[1].displayOrder").value(20))
  }

  @Test
  fun createUpdateAndDeletePaymentMethod() {
    mockMvc
      .perform(
        post("/api/payment-methods")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "交通系IC",
              "displayOrder": 123
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isCreated)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.name").value("交通系IC"))
      .andExpect(jsonPath("$.displayOrder").value(123))

    val paymentMethodId =
      paymentMethodRepository.findByName("交通系IC")?.id ?: error("Payment method id is not assigned")

    mockMvc
      .perform(
        put("/api/payment-methods/{id}", paymentMethodId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "電子マネー",
              "displayOrder": 77
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.id").value(paymentMethodId.toInt()))
      .andExpect(jsonPath("$.name").value("電子マネー"))
      .andExpect(jsonPath("$.displayOrder").value(77))

    mockMvc
      .perform(delete("/api/payment-methods/{id}", paymentMethodId))
      .andExpect(status().isNoContent)

    assertFalse(paymentMethodRepository.existsById(paymentMethodId))
  }

  @Test
  fun invalidPaymentMethodRequestReturnsFieldErrors() {
    mockMvc
      .perform(
        post("/api/payment-methods")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "   ",
              "displayOrder": -1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("入力内容に誤りがあります"))
      .andExpect(jsonPath("$.errors[*].field", hasItems("name", "displayOrder")))
  }

  @Test
  fun duplicatePaymentMethodNameIsRejected() {
    mockMvc
      .perform(
        post("/api/payment-methods")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "現金",
              "displayOrder": 999
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("同じ支払い方法名はすでに存在します"))
      .andExpect(jsonPath("$.errors[0].field").value("name"))
  }

  @Test
  fun missingPaymentMethodReturnsNotFound() {
    mockMvc
      .perform(
        put("/api/payment-methods/{id}", 99999999)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "存在しない支払い方法",
              "displayOrder": 1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isNotFound)
      .andExpect(jsonPath("$.message").value("支払い方法が見つかりません"))
  }

  @Test
  fun usedPaymentMethodCannotBeDeleted() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "支払い方法削除不可テストカテゴリ",
          type = TransactionType.EXPENSE,
          displayOrder = 901,
        )
      )
    val paymentMethod =
      paymentMethodRepository.findByName("現金") ?: error("Payment method is not found")
    val paymentMethodId = paymentMethod.id ?: error("Payment method id is not assigned")
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = category,
        paymentMethod = paymentMethod,
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2026, 7, 9),
        amount = 1000,
        memo = "支払い方法削除不可テスト",
        displayOrder = 1,
      )
    )

    mockMvc
      .perform(delete("/api/payment-methods/{id}", paymentMethodId))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("使用中の支払い方法は削除できません"))

    assertTrue(paymentMethodRepository.existsById(paymentMethodId))
  }

  @Test
  fun lastPaymentMethodCannotBeDeleted() {
    val paymentMethods = paymentMethodRepository.findAll()
    val paymentMethodToKeep = paymentMethods.first()

    paymentMethods.drop(1).forEach { paymentMethodRepository.delete(it) }
    paymentMethodRepository.flush()

    val paymentMethodId = paymentMethodToKeep.id ?: error("Payment method id is not assigned")

    mockMvc
      .perform(delete("/api/payment-methods/{id}", paymentMethodId))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("支払い方法は最低1件必要です"))

    assertTrue(paymentMethodRepository.existsById(paymentMethodId))
  }
}
