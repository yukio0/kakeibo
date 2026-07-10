package jp.yukio0.kakeibo.transfer

import java.time.LocalDate
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
class TransferAccountApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var transferAccountRepository: TransferAccountRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  private lateinit var mockMvc: MockMvc

  @BeforeEach
  fun setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
  }

  @Test
  fun getTransferAccountsReturnsInitialItemsInDisplayOrder() {
    mockMvc
      .perform(get("/api/transfer-accounts"))
      .andExpect(status().isOk)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$[0].name").value("財布"))
      .andExpect(jsonPath("$[0].displayOrder").value(10))
      .andExpect(jsonPath("$[1].name").value("銀行口座"))
      .andExpect(jsonPath("$[1].displayOrder").value(20))
  }

  @Test
  fun createUpdateAndDeleteTransferAccount() {
    mockMvc
      .perform(
        post("/api/transfer-accounts")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "証券口座",
              "displayOrder": 123
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isCreated)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.name").value("証券口座"))
      .andExpect(jsonPath("$.displayOrder").value(123))

    val transferAccountId =
      transferAccountRepository.findByName("証券口座")?.id
        ?: error("Transfer account id is not assigned")

    mockMvc
      .perform(
        put("/api/transfer-accounts/{id}", transferAccountId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "貯金口座",
              "displayOrder": 77
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.id").value(transferAccountId.toInt()))
      .andExpect(jsonPath("$.name").value("貯金口座"))
      .andExpect(jsonPath("$.displayOrder").value(77))

    mockMvc
      .perform(delete("/api/transfer-accounts/{id}", transferAccountId))
      .andExpect(status().isNoContent)

    assertFalse(transferAccountRepository.existsById(transferAccountId))
  }

  @Test
  fun invalidTransferAccountRequestReturnsFieldErrors() {
    mockMvc
      .perform(
        post("/api/transfer-accounts")
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
  fun duplicateTransferAccountNameIsRejected() {
    mockMvc
      .perform(
        post("/api/transfer-accounts")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "財布",
              "displayOrder": 999
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("同じ振替元・振替先名はすでに存在します"))
      .andExpect(jsonPath("$.errors[0].field").value("name"))
  }

  @Test
  fun missingTransferAccountReturnsNotFound() {
    mockMvc
      .perform(
        put("/api/transfer-accounts/{id}", 99999999)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "存在しない振替元・振替先",
              "displayOrder": 1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isNotFound)
      .andExpect(jsonPath("$.message").value("振替元・振替先が見つかりません"))
  }

  @Test
  fun usedTransferAccountCannotBeDeleted() {
    val source = transferAccountRepository.findByName("財布") ?: error("Source is not found")
    val destination =
      transferAccountRepository.findByName("銀行口座") ?: error("Destination is not found")
    val sourceId = source.id ?: error("Transfer source id is not assigned")

    transactionRepository.saveAndFlush(
      TransactionEntity(
        transferSource = source,
        transferDestination = destination,
        type = TransactionType.TRANSFER,
        transactionDate = LocalDate.of(2026, 7, 9),
        amount = 1000,
        memo = "振替元削除不可テスト",
        displayOrder = 1,
      )
    )

    mockMvc
      .perform(delete("/api/transfer-accounts/{id}", sourceId))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("使用中の振替元・振替先は削除できません"))

    assertTrue(transferAccountRepository.existsById(sourceId))
  }

  @Test
  fun lastTransferAccountCannotBeDeleted() {
    val transferAccounts = transferAccountRepository.findAll()
    val transferAccountToKeep = transferAccounts.first()

    transferAccounts.drop(1).forEach { transferAccountRepository.delete(it) }
    transferAccountRepository.flush()

    val transferAccountId = transferAccountToKeep.id ?: error("Transfer account id is not assigned")

    mockMvc
      .perform(delete("/api/transfer-accounts/{id}", transferAccountId))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("振替元・振替先は最低1件必要です"))

    assertTrue(transferAccountRepository.existsById(transferAccountId))
  }
}
