package jp.yukio0.kakeibo.recurring

import java.time.YearMonth
import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.ResourceNotFoundException
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import jp.yukio0.kakeibo.transaction.MonthlyPeriod
import jp.yukio0.kakeibo.transaction.TransactionRepository
import jp.yukio0.kakeibo.transaction.TransactionSaveRequest
import jp.yukio0.kakeibo.transaction.TransactionService
import jp.yukio0.kakeibo.transfer.TransferAccountEntity
import jp.yukio0.kakeibo.transfer.TransferAccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RecurringTransactionService(
  private val templateRepository: RecurringTransactionTemplateRepository,
  private val registrationRepository: RecurringTransactionRegistrationRepository,
  private val categoryRepository: CategoryRepository,
  private val paymentMethodRepository: PaymentMethodRepository,
  private val transferAccountRepository: TransferAccountRepository,
  private val transactionRepository: TransactionRepository,
  private val transactionService: TransactionService,
) {

  @Transactional(readOnly = true)
  fun findAll(): List<RecurringTransactionTemplateResponse> =
    templateRepository.findAllByOrderByDisplayOrderAscIdAsc().map { it.toResponse() }

  @Transactional
  fun create(request: RecurringTransactionTemplateRequest): RecurringTransactionTemplateResponse {
    val targets = resolveTemplateTargets(request)
    val entity =
      RecurringTransactionTemplateEntity(
        name = request.name!!.trim(),
        enabled = request.enabled!!,
        dayOfMonth = request.dayOfMonth!!,
        type = request.type!!,
        category = targets.category,
        paymentMethod = targets.paymentMethod,
        transferSource = targets.transferSource,
        transferDestination = targets.transferDestination,
        defaultAmount = request.defaultAmount,
        memo = request.memo,
        displayOrder = request.displayOrder!!,
      )

    return templateRepository.save(entity).toResponse()
  }

  @Transactional
  fun update(
    id: Long,
    request: RecurringTransactionTemplateRequest,
  ): RecurringTransactionTemplateResponse {
    val entity = findTemplate(id)
    val targets = resolveTemplateTargets(request)

    entity.name = request.name!!.trim()
    entity.enabled = request.enabled!!
    entity.dayOfMonth = request.dayOfMonth!!
    entity.type = request.type!!
    entity.category = targets.category
    entity.paymentMethod = targets.paymentMethod
    entity.transferSource = targets.transferSource
    entity.transferDestination = targets.transferDestination
    entity.defaultAmount = request.defaultAmount
    entity.memo = request.memo
    entity.displayOrder = request.displayOrder!!

    return entity.toResponse()
  }

  @Transactional
  fun delete(id: Long) {
    templateRepository.delete(findTemplate(id))
  }

  @Transactional(readOnly = true)
  fun findCandidates(year: Int?, month: Int?): RecurringTransactionCandidatesResponse {
    val period = MonthlyPeriod.from(year, month)
    val templates = templateRepository.findAllByEnabledTrueOrderByDisplayOrderAscIdAsc()
    val registrations =
      if (templates.isEmpty()) {
        emptyMap()
      } else {
        registrationRepository
          .findAllByTemplateIdInAndTargetYearAndTargetMonth(
            templates.map { it.requiredId() },
            period.year,
            period.month,
          )
          .associateBy { it.template.requiredId() }
      }
    val targetMonth = YearMonth.of(period.year, period.month)

    return RecurringTransactionCandidatesResponse(
      year = period.year,
      month = period.month,
      items =
        templates.map { template ->
          val registration = registrations[template.requiredId()]
          template.toCandidate(targetMonth, registration)
        },
    )
  }

  /** 取引と登録履歴を同じトランザクションで作成し、登録済みテンプレートは冪等に読み飛ばす。 */
  @Transactional
  fun register(request: RecurringTransactionRegisterRequest): RecurringTransactionRegisterResponse {
    val period = MonthlyPeriod.from(request.year, request.month)
    val items = request.items!!
    validateDuplicateTemplateIds(items)

    val templateIds = items.map { it.templateId!! }
    val templates = templateRepository.findAllByIdsForUpdate(templateIds.distinct().sorted())
    val templatesById = templates.associateBy { it.requiredId() }
    if (templatesById.keys != templateIds.toSet()) {
      throw ResourceNotFoundException("定期取引テンプレートが見つかりません")
    }
    validateTemplatesAreEnabled(items, templatesById)

    val registeredTemplateIds =
      registrationRepository
        .findAllByTemplateIdInAndTargetYearAndTargetMonth(
          templateIds,
          period.year,
          period.month,
        )
        .mapTo(linkedSetOf()) { it.template.requiredId() }
    val pendingItems = items.withIndex().filter { it.value.templateId !in registeredTemplateIds }
    validateRegistrationTargets(pendingItems)

    if (pendingItems.isEmpty()) {
      return RecurringTransactionRegisterResponse(
        created = emptyList(),
        skippedTemplateIds = templateIds,
      )
    }

    val created =
      transactionService.createBatch(
        period.year,
        period.month,
        pendingItems.map { it.value.toTransactionRequest() },
      )
    val registrations =
      pendingItems.zip(created).map { (item, transaction) ->
        RecurringTransactionRegistrationEntity(
          template = templatesById.getValue(item.value.templateId!!),
          targetYear = period.year,
          targetMonth = period.month,
          transaction = transactionRepository.getReferenceById(transaction.id),
        )
      }
    registrationRepository.saveAll(registrations)

    return RecurringTransactionRegisterResponse(
      created = created,
      skippedTemplateIds =
        items.mapNotNull { it.templateId?.takeIf(registeredTemplateIds::contains) },
    )
  }

  private fun resolveTemplateTargets(
    request: RecurringTransactionTemplateRequest
  ): TemplateTargets {
    val errors = targetErrors(request.type!!, request)
    if (errors.isNotEmpty()) {
      throw ApiValidationException("入力内容に誤りがあります", errors)
    }

    return when (request.type) {
      TransactionType.EXPENSE -> {
        val category = findCategory(request.categoryId!!)
        validateCategoryType(category, TransactionType.EXPENSE, "categoryId")
        TemplateTargets(
          category = category,
          paymentMethod = findPaymentMethod(request.paymentMethodId!!),
        )
      }
      TransactionType.INCOME -> {
        val category = findCategory(request.categoryId!!)
        validateCategoryType(category, TransactionType.INCOME, "categoryId")
        TemplateTargets(category = category)
      }
      TransactionType.TRANSFER ->
        TemplateTargets(
          transferSource = findTransferAccount(request.transferSourceId!!),
          transferDestination = findTransferAccount(request.transferDestinationId!!),
        )
    }
  }

  private fun targetErrors(
    type: TransactionType,
    request: RecurringTransactionTemplateRequest,
  ): List<ApiFieldErrorResponse> =
    targetErrors(
      type = type,
      categoryId = request.categoryId,
      paymentMethodId = request.paymentMethodId,
      transferSourceId = request.transferSourceId,
      transferDestinationId = request.transferDestinationId,
      fieldPrefix = "",
    )

  private fun validateRegistrationTargets(
    items: List<IndexedValue<RecurringTransactionRegisterItem>>
  ) {
    val errors = items.flatMap { (index, item) ->
      targetErrors(
        type = item.type!!,
        categoryId = item.categoryId,
        paymentMethodId = item.paymentMethodId,
        transferSourceId = item.transferSourceId,
        transferDestinationId = item.transferDestinationId,
        fieldPrefix = "items[$index].",
      )
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("入力内容に誤りがあります", errors)
    }
  }

  private fun targetErrors(
    type: TransactionType,
    categoryId: Long?,
    paymentMethodId: Long?,
    transferSourceId: Long?,
    transferDestinationId: Long?,
    fieldPrefix: String,
  ): List<ApiFieldErrorResponse> =
    when (type) {
      TransactionType.EXPENSE ->
        listOfNotNull(
          requiredTargetError(fieldPrefix + "categoryId", categoryId, "カテゴリを選択してください"),
          requiredTargetError(
            fieldPrefix + "paymentMethodId",
            paymentMethodId,
            "支払い方法を選択してください",
          ),
          forbiddenTargetError(
            fieldPrefix + "transferSourceId",
            transferSourceId,
            "支出では振替元を指定できません",
          ),
          forbiddenTargetError(
            fieldPrefix + "transferDestinationId",
            transferDestinationId,
            "支出では振替先を指定できません",
          ),
        )
      TransactionType.INCOME ->
        listOfNotNull(
          requiredTargetError(fieldPrefix + "categoryId", categoryId, "カテゴリを選択してください"),
          forbiddenTargetError(
            fieldPrefix + "paymentMethodId",
            paymentMethodId,
            "収入では支払い方法を指定できません",
          ),
          forbiddenTargetError(
            fieldPrefix + "transferSourceId",
            transferSourceId,
            "収入では振替元を指定できません",
          ),
          forbiddenTargetError(
            fieldPrefix + "transferDestinationId",
            transferDestinationId,
            "収入では振替先を指定できません",
          ),
        )
      TransactionType.TRANSFER ->
        listOfNotNull(
          forbiddenTargetError(
            fieldPrefix + "categoryId",
            categoryId,
            "振替ではカテゴリを指定できません",
          ),
          forbiddenTargetError(
            fieldPrefix + "paymentMethodId",
            paymentMethodId,
            "振替では支払い方法を指定できません",
          ),
          requiredTargetError(
            fieldPrefix + "transferSourceId",
            transferSourceId,
            "振替元を選択してください",
          ),
          requiredTargetError(
            fieldPrefix + "transferDestinationId",
            transferDestinationId,
            "振替先を選択してください",
          ),
        )
    }

  private fun requiredTargetError(
    field: String,
    value: Long?,
    message: String,
  ): ApiFieldErrorResponse? =
    if (value == null) ApiFieldErrorResponse(field = field, message = message) else null

  private fun forbiddenTargetError(
    field: String,
    value: Long?,
    message: String,
  ): ApiFieldErrorResponse? =
    if (value != null) ApiFieldErrorResponse(field = field, message = message) else null

  private fun validateDuplicateTemplateIds(items: List<RecurringTransactionRegisterItem>) {
    val seen = mutableSetOf<Long>()
    val errors = items.mapIndexedNotNull { index, item ->
      item.templateId
        ?.takeIf { !seen.add(it) }
        ?.let {
          ApiFieldErrorResponse(
            field = "items[$index].templateId",
            message = "同じ定期取引テンプレートが重複しています",
          )
        }
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("同じ定期取引テンプレートが重複しています", errors)
    }
  }

  private fun validateTemplatesAreEnabled(
    items: List<RecurringTransactionRegisterItem>,
    templatesById: Map<Long, RecurringTransactionTemplateEntity>,
  ) {
    val errors = items.mapIndexedNotNull { index, item ->
      item.templateId
        ?.takeIf { !templatesById.getValue(it).enabled }
        ?.let {
          ApiFieldErrorResponse(
            field = "items[$index].templateId",
            message = "停止中の定期取引テンプレートは登録できません",
          )
        }
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("停止中の定期取引テンプレートは登録できません", errors)
    }
  }

  private fun RecurringTransactionRegisterItem.toTransactionRequest(): TransactionSaveRequest =
    when (type!!) {
      TransactionType.EXPENSE ->
        TransactionSaveRequest(
          date = date,
          type = type,
          categoryId = categoryId,
          paymentMethodId = paymentMethodId,
          amount = amount,
          memo = memo,
        )
      TransactionType.INCOME ->
        TransactionSaveRequest(
          date = date,
          type = type,
          categoryId = categoryId,
          paymentMethodId = null,
          amount = amount,
          memo = memo,
        )
      TransactionType.TRANSFER ->
        TransactionSaveRequest(
          date = date,
          type = type,
          categoryId = transferSourceId,
          paymentMethodId = transferDestinationId,
          amount = amount,
          memo = memo,
        )
    }

  private fun findTemplate(id: Long): RecurringTransactionTemplateEntity =
    templateRepository.findById(id).orElseThrow {
      ResourceNotFoundException("定期取引テンプレートが見つかりません")
    }

  private fun findCategory(id: Long): CategoryEntity =
    categoryRepository.findById(id).orElseThrow { ResourceNotFoundException("カテゴリが見つかりません") }

  private fun findPaymentMethod(id: Long): PaymentMethodEntity =
    paymentMethodRepository.findById(id).orElseThrow {
      ResourceNotFoundException("支払い方法が見つかりません")
    }

  private fun findTransferAccount(id: Long): TransferAccountEntity =
    transferAccountRepository.findById(id).orElseThrow {
      ResourceNotFoundException("振替元・振替先が見つかりません")
    }

  private fun validateCategoryType(
    category: CategoryEntity,
    type: TransactionType,
    field: String,
  ) {
    if (category.type != type) {
      throw ApiValidationException(
        message = "種別に合うカテゴリを選択してください",
        errors = listOf(ApiFieldErrorResponse(field = field, message = "種別に合うカテゴリを選択してください")),
      )
    }
  }

  private fun RecurringTransactionTemplateEntity.toResponse() =
    RecurringTransactionTemplateResponse(
      id = requiredId(),
      name = name,
      enabled = enabled,
      dayOfMonth = dayOfMonth,
      type = type,
      categoryId = category?.requiredId(),
      categoryName = category?.name,
      paymentMethodId = paymentMethod?.requiredId(),
      paymentMethodName = paymentMethod?.name,
      transferSourceId = transferSource?.requiredId(),
      transferSourceName = transferSource?.name,
      transferDestinationId = transferDestination?.requiredId(),
      transferDestinationName = transferDestination?.name,
      defaultAmount = defaultAmount,
      memo = memo,
      displayOrder = displayOrder,
    )

  private fun RecurringTransactionTemplateEntity.toCandidate(
    targetMonth: YearMonth,
    registration: RecurringTransactionRegistrationEntity?,
  ): RecurringTransactionCandidateResponse {
    if (registration != null) {
      val transaction = registration.transaction
      val isTransfer = transaction.type == TransactionType.TRANSFER
      return RecurringTransactionCandidateResponse(
        templateId = requiredId(),
        templateName = name,
        registered = true,
        transactionId = transaction.requiredId(),
        date = transaction.transactionDate.toString(),
        type = transaction.type,
        categoryId = if (isTransfer) null else transaction.category?.requiredId(),
        categoryName = if (isTransfer) null else transaction.category?.name,
        paymentMethodId = if (isTransfer) null else transaction.paymentMethod?.requiredId(),
        paymentMethodName = if (isTransfer) null else transaction.paymentMethod?.name,
        transferSourceId = if (isTransfer) transaction.transferSource?.requiredId() else null,
        transferSourceName = if (isTransfer) transaction.transferSource?.name else null,
        transferDestinationId =
          if (isTransfer) transaction.transferDestination?.requiredId() else null,
        transferDestinationName = if (isTransfer) transaction.transferDestination?.name else null,
        amount = transaction.amount,
        memo = transaction.memo,
      )
    }

    return RecurringTransactionCandidateResponse(
      templateId = requiredId(),
      templateName = name,
      registered = false,
      transactionId = null,
      date = targetMonth.atDay(dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth())).toString(),
      type = type,
      categoryId = category?.requiredId(),
      categoryName = category?.name,
      paymentMethodId = paymentMethod?.requiredId(),
      paymentMethodName = paymentMethod?.name,
      transferSourceId = transferSource?.requiredId(),
      transferSourceName = transferSource?.name,
      transferDestinationId = transferDestination?.requiredId(),
      transferDestinationName = transferDestination?.name,
      amount = defaultAmount,
      memo = memo,
    )
  }

  private data class TemplateTargets(
    val category: CategoryEntity? = null,
    val paymentMethod: PaymentMethodEntity? = null,
    val transferSource: TransferAccountEntity? = null,
    val transferDestination: TransferAccountEntity? = null,
  )
}
