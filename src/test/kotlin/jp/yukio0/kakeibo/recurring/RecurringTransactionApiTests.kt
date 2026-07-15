package jp.yukio0.kakeibo.recurring

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
import org.hamcrest.Matchers.hasItem
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecurringTransactionApiTests {

  @Autowired private lateinit var context: WebApplicationContext

  @Autowired private lateinit var objectMapper: ObjectMapper

  @Autowired private lateinit var categoryRepository: CategoryRepository

  @Autowired private lateinit var paymentMethodRepository: PaymentMethodRepository

  @Autowired private lateinit var transferAccountRepository: TransferAccountRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  @Autowired private lateinit var templateRepository: RecurringTransactionTemplateRepository

  @Autowired private lateinit var registrationRepository: RecurringTransactionRegistrationRepository

  private lateinit var mockMvc: MockMvc

  @BeforeEach
  fun setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
  }

  @Test
  fun crudSupportsEveryTransactionTypeAndNullableDefaultAmount() {
    val expense = saveCategory("Recurring CRUD expense", TransactionType.EXPENSE)
    val income = saveCategory("Recurring CRUD income", TransactionType.INCOME)
    val paymentMethod = savePaymentMethod("Recurring CRUD cash")
    val source = saveTransferAccount("Recurring CRUD source")
    val destination = saveTransferAccount("Recurring CRUD destination")

    val expenseResponse =
      createTemplate(
        templateRequest(
          name = " Rent ",
          dayOfMonth = 27,
          type = TransactionType.EXPENSE,
          categoryId = expense.requiredId(),
          paymentMethodId = paymentMethod.requiredId(),
          defaultAmount = 80_000,
          memo = "Monthly rent",
          displayOrder = 30,
        )
      )
    val expenseId = expenseResponse.path("id").asLong()
    assertEquals("Rent", expenseResponse.path("name").textValue())
    assertEquals(80_000, expenseResponse.path("defaultAmount").asInt())

    val incomeResponse =
      createTemplate(
        templateRequest(
          name = "Salary",
          dayOfMonth = 25,
          type = TransactionType.INCOME,
          categoryId = income.requiredId(),
          defaultAmount = null,
          displayOrder = 10,
        )
      )
    assertTrue(incomeResponse.path("defaultAmount").isNull)
    assertTrue(incomeResponse.path("paymentMethodId").isNull)

    createTemplate(
      templateRequest(
        name = "Asset transfer",
        dayOfMonth = 31,
        type = TransactionType.TRANSFER,
        transferSourceId = source.requiredId(),
        transferDestinationId = destination.requiredId(),
        defaultAmount = 10_000,
        displayOrder = 20,
      )
    )

    val list = getJson("/api/recurring-templates")
    assertEquals("Salary", list.path(0).path("name").textValue())
    assertEquals("Asset transfer", list.path(1).path("name").textValue())
    assertEquals("Rent", list.path(2).path("name").textValue())

    mockMvc
      .perform(
        put("/api/recurring-templates/{id}", expenseId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            json(
              templateRequest(
                name = "Updated income",
                enabled = false,
                dayOfMonth = 1,
                type = TransactionType.INCOME,
                categoryId = income.requiredId(),
                defaultAmount = 300_000,
                displayOrder = 5,
              )
            )
          )
      )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.name").value("Updated income"))
      .andExpect(jsonPath("$.enabled").value(false))
      .andExpect(jsonPath("$.paymentMethodId").doesNotExist())

    mockMvc
      .perform(delete("/api/recurring-templates/{id}", expenseId))
      .andExpect(status().isNoContent)
    assertFalse(templateRepository.existsById(expenseId))
  }

  @Test
  fun templateRejectsCategoryTypeMismatchAndTargetsForAnotherType() {
    val expense = saveCategory("Recurring validation expense", TransactionType.EXPENSE)
    val income = saveCategory("Recurring validation income", TransactionType.INCOME)
    val paymentMethod = savePaymentMethod("Recurring validation card")
    val source = saveTransferAccount("Recurring validation source")

    expectTemplateBadRequest(
      templateRequest(
        name = "Wrong category",
        dayOfMonth = 1,
        type = TransactionType.EXPENSE,
        categoryId = income.requiredId(),
        paymentMethodId = paymentMethod.requiredId(),
      )
    )
    expectTemplateBadRequest(
      templateRequest(
        name = "Income with payment method",
        dayOfMonth = 1,
        type = TransactionType.INCOME,
        categoryId = income.requiredId(),
        paymentMethodId = paymentMethod.requiredId(),
      )
    )
    expectTemplateBadRequest(
      templateRequest(
        name = "Transfer without destination",
        dayOfMonth = 1,
        type = TransactionType.TRANSFER,
        transferSourceId = source.requiredId(),
      )
    )
    expectTemplateBadRequest(
      templateRequest(
        name = "Invalid amount",
        dayOfMonth = 1,
        type = TransactionType.EXPENSE,
        categoryId = expense.requiredId(),
        paymentMethodId = paymentMethod.requiredId(),
        defaultAmount = 0,
      )
    )
  }

  @Test
  fun candidatesExcludeDisabledTemplatesAndAdjustDayToMonthEndIncludingLeapYear() {
    val expense = saveCategory("Recurring candidate expense", TransactionType.EXPENSE)
    val paymentMethod = savePaymentMethod("Recurring candidate cash")
    createTemplate(
      templateRequest(
        name = "Month end",
        dayOfMonth = 31,
        type = TransactionType.EXPENSE,
        categoryId = expense.requiredId(),
        paymentMethodId = paymentMethod.requiredId(),
        defaultAmount = null,
        displayOrder = 1,
      )
    )
    createTemplate(
      templateRequest(
        name = "Stopped",
        enabled = false,
        dayOfMonth = 1,
        type = TransactionType.EXPENSE,
        categoryId = expense.requiredId(),
        paymentMethodId = paymentMethod.requiredId(),
        defaultAmount = 1,
        displayOrder = 2,
      )
    )

    val commonYear = candidates(2091, 2)
    assertEquals(1, commonYear.path("items").size())
    assertEquals("Month end", commonYear.path("items").path(0).path("templateName").textValue())
    assertEquals("2091-02-28", commonYear.path("items").path(0).path("date").textValue())
    assertTrue(commonYear.path("items").path(0).path("amount").isNull)

    val leapYear = candidates(2092, 2)
    assertEquals("2092-02-29", leapYear.path("items").path(0).path("date").textValue())
  }

  @Test
  fun registerSupportsAllTypesOverridesCandidateValuesAndKeepsExistingTransactions() {
    val expense = saveCategory("Recurring register expense", TransactionType.EXPENSE)
    val income = saveCategory("Recurring register income", TransactionType.INCOME)
    val paymentMethod = savePaymentMethod("Recurring register cash")
    val source = saveTransferAccount("Recurring register source")
    val destination = saveTransferAccount("Recurring register destination")
    val expenseTemplate =
      createTemplateId(
        templateRequest(
          name = "Variable utility",
          dayOfMonth = 31,
          type = TransactionType.EXPENSE,
          categoryId = expense.requiredId(),
          paymentMethodId = paymentMethod.requiredId(),
          defaultAmount = null,
          memo = "template memo",
          displayOrder = 1,
        )
      )
    val incomeTemplate =
      createTemplateId(
        templateRequest(
          name = "Salary",
          dayOfMonth = 25,
          type = TransactionType.INCOME,
          categoryId = income.requiredId(),
          defaultAmount = 300_000,
          displayOrder = 2,
        )
      )
    val transferTemplate =
      createTemplateId(
        templateRequest(
          name = "Savings",
          dayOfMonth = 26,
          type = TransactionType.TRANSFER,
          transferSourceId = source.requiredId(),
          transferDestinationId = destination.requiredId(),
          defaultAmount = 50_000,
          displayOrder = 3,
        )
      )
    val existing =
      transactionRepository.saveAndFlush(
        TransactionEntity(
          category = expense,
          paymentMethod = paymentMethod,
          type = TransactionType.EXPENSE,
          transactionDate = LocalDate.of(2093, 4, 1),
          amount = 999,
          memo = "Existing transaction",
          displayOrder = 1,
        )
      )

    val response =
      register(
        2093,
        4,
        listOf(
          registerItem(
            expenseTemplate,
            "2093-04-29",
            TransactionType.EXPENSE,
            categoryId = expense.requiredId(),
            paymentMethodId = paymentMethod.requiredId(),
            amount = 12_345,
            memo = "overridden memo",
          ),
          registerItem(
            incomeTemplate,
            "2093-04-25",
            TransactionType.INCOME,
            categoryId = income.requiredId(),
            amount = 301_000,
          ),
          registerItem(
            transferTemplate,
            "2093-04-26",
            TransactionType.TRANSFER,
            transferSourceId = source.requiredId(),
            transferDestinationId = destination.requiredId(),
            amount = 51_000,
          ),
        ),
      )

    assertEquals(3, response.path("created").size())
    assertEquals(0, response.path("skippedTemplateIds").size())
    val monthly = transactionsIn(2093, 4)
    assertEquals(4, monthly.size)
    assertTrue(monthly.any { it.id == existing.id })
    val utility = monthly.single { it.memo == "overridden memo" }
    assertEquals(LocalDate.of(2093, 4, 29), utility.transactionDate)
    assertEquals(12_345, utility.amount)
    assertEquals(
      1,
      registrationRepository.findAll().count {
        it.targetMonth == 4 && it.template.requiredId() == expenseTemplate
      },
    )
  }

  @Test
  fun registrationIsIdempotentForSameMonthAndAllowsAnotherMonth() {
    val fixture = expenseFixture("Recurring idempotent")
    val templateId = createExpenseTemplate(fixture, defaultAmount = 500)
    val julyItem = fixture.registerItem(templateId, "2094-07-10", amount = 500)

    assertEquals(1, register(2094, 7, listOf(julyItem)).path("created").size())
    val retry = register(2094, 7, listOf(julyItem))
    assertEquals(0, retry.path("created").size())
    assertEquals(templateId, retry.path("skippedTemplateIds").path(0).asLong())

    val augustItem = fixture.registerItem(templateId, "2094-08-10", amount = 600)
    assertEquals(1, register(2094, 8, listOf(augustItem)).path("created").size())
    assertEquals(2L, registrationRepository.count())
  }

  @Test
  fun duplicateTemplateIdsAreRejectedWithoutCreatingTransactions() {
    val fixture = expenseFixture("Recurring duplicate")
    val templateId = createExpenseTemplate(fixture, defaultAmount = 500)
    val item = fixture.registerItem(templateId, "2095-01-10", amount = 500)
    val before = transactionRepository.count()

    mockMvc
      .perform(
        post("/api/recurring-templates/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content(json(RecurringTransactionRegisterRequest(2095, 1, listOf(item, item))))
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.errors[*].field", hasItem("items[1].templateId")))

    assertEquals(before, transactionRepository.count())
    assertEquals(0L, registrationRepository.count())
  }

  @Test
  fun invalidItemRollsBackEveryPendingRegistration() {
    val fixture = expenseFixture("Recurring rollback")
    val firstTemplate = createExpenseTemplate(fixture, name = "Rollback one", defaultAmount = 100)
    val secondTemplate = createExpenseTemplate(fixture, name = "Rollback two", defaultAmount = 200)
    val before = transactionRepository.count()

    mockMvc
      .perform(
        post("/api/recurring-templates/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            json(
              RecurringTransactionRegisterRequest(
                2095,
                2,
                listOf(
                  fixture.registerItem(firstTemplate, "2095-02-10", amount = 100),
                  fixture.registerItem(secondTemplate, "2095-03-10", amount = 200),
                ),
              )
            )
          )
      )
      .andExpect(status().isBadRequest)

    assertEquals(before, transactionRepository.count())
    assertEquals(0L, registrationRepository.count())
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  fun deletingGeneratedTransactionMakesTemplateRegisterableAgain() {
    val fixture = existingExpenseFixture()
    val templateId = createExpenseTemplate(fixture, defaultAmount = 800)
    val item = fixture.registerItem(templateId, "2096-03-10", amount = 800)
    val transactionId = register(2096, 3, listOf(item)).path("created").path(0).path("id").asLong()

    mockMvc
      .perform(
        delete("/api/transactions/{id}", transactionId).param("year", "2096").param("month", "3")
      )
      .andExpect(status().isNoContent)

    assertFalse(transactionRepository.existsById(transactionId))
    assertFalse(candidates(2096, 3).path("items").path(0).path("registered").asBoolean())
    val replacementId = register(2096, 3, listOf(item)).path("created").path(0).path("id").asLong()
    assertTrue(transactionRepository.existsById(replacementId))

    mockMvc
      .perform(
        delete("/api/transactions/{id}", replacementId).param("year", "2096").param("month", "3")
      )
      .andExpect(status().isNoContent)
    mockMvc
      .perform(delete("/api/recurring-templates/{id}", templateId))
      .andExpect(status().isNoContent)
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  fun deletingTemplateKeepsGeneratedTransaction() {
    val fixture = existingExpenseFixture()
    val templateId = createExpenseTemplate(fixture, defaultAmount = 900)
    val transactionId =
      register(2096, 4, listOf(fixture.registerItem(templateId, "2096-04-10", amount = 900)))
        .path("created")
        .path(0)
        .path("id")
        .asLong()

    mockMvc
      .perform(delete("/api/recurring-templates/{id}", templateId))
      .andExpect(status().isNoContent)

    assertFalse(templateRepository.existsById(templateId))
    assertTrue(transactionRepository.existsById(transactionId))
    assertEquals(0L, registrationRepository.count())

    mockMvc
      .perform(
        delete("/api/transactions/{id}", transactionId).param("year", "2096").param("month", "4")
      )
      .andExpect(status().isNoContent)
  }

  @Test
  fun editingTemplateDoesNotChangePastTransactionAndAffectsLaterCandidates() {
    val fixture = expenseFixture("Recurring non-retroactive")
    val templateId = createExpenseTemplate(fixture, defaultAmount = 1_000, memo = "old memo")
    val transactionId =
      register(
          2096,
          5,
          listOf(fixture.registerItem(templateId, "2096-05-10", amount = 1_000, memo = "old memo")),
        )
        .path("created")
        .path(0)
        .path("id")
        .asLong()

    mockMvc
      .perform(
        put("/api/recurring-templates/{id}", templateId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            json(
              templateRequest(
                name = "Changed template",
                dayOfMonth = 20,
                type = TransactionType.EXPENSE,
                categoryId = fixture.category.requiredId(),
                paymentMethodId = fixture.paymentMethod.requiredId(),
                defaultAmount = 2_000,
                memo = "new memo",
              )
            )
          )
      )
      .andExpect(status().isOk)

    val past = transactionRepository.findById(transactionId).orElseThrow()
    assertEquals(1_000, past.amount)
    assertEquals("old memo", past.memo)
    assertEquals(LocalDate.of(2096, 5, 10), past.transactionDate)
    val registered = candidates(2096, 5).path("items").path(0)
    assertTrue(registered.path("registered").asBoolean())
    assertEquals("2096-05-10", registered.path("date").textValue())
    assertEquals(1_000, registered.path("amount").asInt())
    assertEquals("old memo", registered.path("memo").textValue())
    val next = candidates(2096, 6).path("items").path(0)
    assertEquals("2096-06-20", next.path("date").textValue())
    assertEquals(2_000, next.path("amount").asInt())
    assertEquals("new memo", next.path("memo").textValue())
  }

  @Test
  fun mastersReferencedByTemplateCannotBeDeletedAndCategoryTypeCannotChange() {
    val fixture = expenseFixture("Recurring protected master")
    createExpenseTemplate(fixture, defaultAmount = 100)
    val source = saveTransferAccount("Recurring protected source")
    val destination = saveTransferAccount("Recurring protected destination")
    createTemplate(
      templateRequest(
        name = "Protected transfer",
        dayOfMonth = 1,
        type = TransactionType.TRANSFER,
        transferSourceId = source.requiredId(),
        transferDestinationId = destination.requiredId(),
        defaultAmount = 100,
      )
    )

    mockMvc
      .perform(delete("/api/categories/{id}", fixture.category.requiredId()))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("使用中のカテゴリは削除できません"))
    mockMvc
      .perform(
        put("/api/categories/{id}", fixture.category.requiredId())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "name": "Changed category type",
              "type": "INCOME",
              "displayOrder": 1
            }
            """
              .trimIndent()
          )
      )
      .andExpect(status().isBadRequest)
    mockMvc
      .perform(delete("/api/payment-methods/{id}", fixture.paymentMethod.requiredId()))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("使用中の支払い方法は削除できません"))
    mockMvc
      .perform(delete("/api/transfer-accounts/{id}", source.requiredId()))
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.message").value("使用中の振替元・振替先は削除できません"))
  }

  @Test
  fun stoppedTemplateCannotBeRegistered() {
    val fixture = expenseFixture("Recurring stopped")
    val templateId =
      createTemplateId(
        templateRequest(
          name = "Stopped template",
          enabled = false,
          dayOfMonth = 1,
          type = TransactionType.EXPENSE,
          categoryId = fixture.category.requiredId(),
          paymentMethodId = fixture.paymentMethod.requiredId(),
          defaultAmount = 100,
        )
      )

    mockMvc
      .perform(
        post("/api/recurring-templates/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            json(
              RecurringTransactionRegisterRequest(
                2097,
                1,
                listOf(fixture.registerItem(templateId, "2097-01-01", amount = 100)),
              )
            )
          )
      )
      .andExpect(status().isBadRequest)
      .andExpect(jsonPath("$.errors[0].field").value("items[0].templateId"))
  }

  private fun createExpenseTemplate(
    fixture: ExpenseFixture,
    name: String = "Recurring expense",
    defaultAmount: Int?,
    memo: String? = null,
  ): Long =
    createTemplateId(
      templateRequest(
        name = name,
        dayOfMonth = 10,
        type = TransactionType.EXPENSE,
        categoryId = fixture.category.requiredId(),
        paymentMethodId = fixture.paymentMethod.requiredId(),
        defaultAmount = defaultAmount,
        memo = memo,
      )
    )

  private fun createTemplate(request: RecurringTransactionTemplateRequest): JsonNode {
    val result =
      mockMvc
        .perform(
          post("/api/recurring-templates")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(request))
        )
        .andExpect(status().isCreated)
        .andReturn()
    return objectMapper.readTree(result.response.contentAsString)
  }

  private fun createTemplateId(request: RecurringTransactionTemplateRequest): Long =
    createTemplate(request).path("id").asLong()

  private fun expectTemplateBadRequest(request: RecurringTransactionTemplateRequest) {
    mockMvc
      .perform(
        post("/api/recurring-templates")
          .contentType(MediaType.APPLICATION_JSON)
          .content(json(request))
      )
      .andExpect(status().isBadRequest)
  }

  private fun candidates(year: Int, month: Int): JsonNode =
    getJson("/api/recurring-templates/candidates", year, month)

  private fun getJson(path: String, year: Int? = null, month: Int? = null): JsonNode {
    val request = get(path)
    year?.let { request.param("year", it.toString()) }
    month?.let { request.param("month", it.toString()) }
    val result = mockMvc.perform(request).andExpect(status().isOk).andReturn()
    return objectMapper.readTree(result.response.contentAsString)
  }

  private fun register(
    year: Int,
    month: Int,
    items: List<RecurringTransactionRegisterItem>,
  ): JsonNode {
    val result =
      mockMvc
        .perform(
          post("/api/recurring-templates/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(RecurringTransactionRegisterRequest(year, month, items)))
        )
        .andExpect(status().isOk)
        .andReturn()
    return objectMapper.readTree(result.response.contentAsString)
  }

  private fun registerItem(
    templateId: Long,
    date: String,
    type: TransactionType,
    categoryId: Long? = null,
    paymentMethodId: Long? = null,
    transferSourceId: Long? = null,
    transferDestinationId: Long? = null,
    amount: Int,
    memo: String? = null,
  ) =
    RecurringTransactionRegisterItem(
      templateId = templateId,
      date = date,
      type = type,
      categoryId = categoryId,
      paymentMethodId = paymentMethodId,
      transferSourceId = transferSourceId,
      transferDestinationId = transferDestinationId,
      amount = amount,
      memo = memo,
    )

  private fun templateRequest(
    name: String,
    enabled: Boolean = true,
    dayOfMonth: Int,
    type: TransactionType,
    categoryId: Long? = null,
    paymentMethodId: Long? = null,
    transferSourceId: Long? = null,
    transferDestinationId: Long? = null,
    defaultAmount: Int? = null,
    memo: String? = null,
    displayOrder: Int = 0,
  ) =
    RecurringTransactionTemplateRequest(
      name = name,
      enabled = enabled,
      dayOfMonth = dayOfMonth,
      type = type,
      categoryId = categoryId,
      paymentMethodId = paymentMethodId,
      transferSourceId = transferSourceId,
      transferDestinationId = transferDestinationId,
      defaultAmount = defaultAmount,
      memo = memo,
      displayOrder = displayOrder,
    )

  private fun expenseFixture(name: String) =
    ExpenseFixture(
      category = saveCategory("$name category", TransactionType.EXPENSE),
      paymentMethod = savePaymentMethod("$name payment"),
    )

  private fun existingExpenseFixture(): ExpenseFixture =
    ExpenseFixture(
      category =
        categoryRepository.findByNameAndType("食費", TransactionType.EXPENSE)
          ?: error("Initial expense category is missing"),
      paymentMethod =
        paymentMethodRepository.findByName("現金") ?: error("Initial payment method is missing"),
    )

  private fun ExpenseFixture.registerItem(
    templateId: Long,
    date: String,
    amount: Int,
    memo: String? = null,
  ) =
    registerItem(
      templateId = templateId,
      date = date,
      type = TransactionType.EXPENSE,
      categoryId = category.requiredId(),
      paymentMethodId = paymentMethod.requiredId(),
      amount = amount,
      memo = memo,
    )

  private fun transactionsIn(year: Int, month: Int): List<TransactionEntity> =
    transactionRepository.findAll().filter {
      it.transactionDate.year == year && it.transactionDate.monthValue == month
    }

  private fun saveCategory(name: String, type: TransactionType): CategoryEntity =
    categoryRepository.saveAndFlush(CategoryEntity(name = name, type = type, displayOrder = 990))

  private fun savePaymentMethod(name: String): PaymentMethodEntity =
    paymentMethodRepository.saveAndFlush(PaymentMethodEntity(name = name, displayOrder = 990))

  private fun saveTransferAccount(name: String): TransferAccountEntity =
    transferAccountRepository.saveAndFlush(TransferAccountEntity(name = name, displayOrder = 990))

  private fun json(value: Any): String = objectMapper.writeValueAsString(value)

  private data class ExpenseFixture(
    val category: CategoryEntity,
    val paymentMethod: PaymentMethodEntity,
  )
}
