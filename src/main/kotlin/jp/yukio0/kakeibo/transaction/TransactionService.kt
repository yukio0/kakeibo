package jp.yukio0.kakeibo.transaction

import jakarta.validation.Validator
import java.time.LocalDate
import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.ResourceNotFoundException
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionTargetField
import jp.yukio0.kakeibo.domain.TransactionTargetIds
import jp.yukio0.kakeibo.domain.TransactionTargetRules
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import jp.yukio0.kakeibo.transfer.TransferAccountEntity
import jp.yukio0.kakeibo.transfer.TransferAccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(
  private val transactionRepository: TransactionRepository,
  private val categoryRepository: CategoryRepository,
  private val paymentMethodRepository: PaymentMethodRepository,
  private val transferAccountRepository: TransferAccountRepository,
  private val validator: Validator,
) {

  @Transactional(readOnly = true)
  fun findMonthly(year: Int?, month: Int?): List<TransactionResponse> {
    val monthlyPeriod = MonthlyPeriod.from(year, month)

    return findMonthlyTransactions(monthlyPeriod).map { it.toResponse() }
  }

  @Transactional(readOnly = true)
  fun exportCsv(exportPeriod: TransactionExportPeriod): ByteArray? {
    val rows = findTransactionsForExport(exportPeriod).map { it.toResponse() }
    if (rows.isEmpty()) {
      return null
    }
    return TransactionCsvCodec.encode(
      rows.map { transaction ->
        TransactionCsvRecord(
          date = transaction.date,
          type = transaction.type,
          categoryOrTransferSource = transaction.categoryName,
          paymentMethodOrTransferDestination = transaction.paymentMethodName.orEmpty(),
          amount = transaction.amount.toString(),
          memo = transaction.memo.orEmpty(),
        )
      }
    )
  }

  @Transactional
  fun create(year: Int?, month: Int?, request: TransactionSaveRequest): TransactionResponse =
    saveOne(year, month, id = null, request)

  /** 対象月の既存データを残したまま、複数の家計簿データを一括追加する。 */
  @Transactional
  fun createBatch(
    year: Int?,
    month: Int?,
    requests: List<TransactionSaveRequest>,
  ): List<TransactionResponse> {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
    val currentMaxDisplayOrder =
      findMonthlyTransactions(monthlyPeriod).maxOfOrNull { it.displayOrder } ?: -10
    val monthlyRequests = requests.mapIndexed { index, request ->
      request.toMonthlySaveRequest(
        id = null,
        displayOrder = currentMaxDisplayOrder + (index + 1) * 10,
      )
    }
    validateBeanConstraints(monthlyRequests)

    val commands = monthlyRequests.mapIndexed { index, request ->
      request.toCommand(index, monthlyPeriod)
    }
    val targets = resolveTargets(commands)

    return commands
      .map { command ->
        val transaction =
          TransactionEntity(
            type = command.type,
            transactionDate = command.transactionDate,
            amount = command.amount,
          )
        applyCommand(transaction, command, targets)
        transactionRepository.save(transaction)
      }
      .map { it.toResponse() }
  }

  @Transactional
  fun update(
    year: Int?,
    month: Int?,
    id: Long,
    request: TransactionSaveRequest,
  ): TransactionResponse = saveOne(year, month, id, request)

  @Transactional
  fun delete(year: Int?, month: Int?, id: Long) {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
    val transaction = findTransactionInMonth(id, monthlyPeriod)
    transactionRepository.delete(transaction)
  }

  /** 保存後の家計簿データを、リクエストと同じ並びで返す。呼び出し側は位置で新規行のIDを引き当てられる。 */
  @Transactional
  fun saveMonthly(
    year: Int?,
    month: Int?,
    requests: List<TransactionMonthlySaveRequest>,
  ): List<TransactionResponse> {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
    // この呼び出しの開始後に追加された行を、月次置換の削除対象へ含めない。
    val monthlySnapshot = findMonthlyTransactions(monthlyPeriod)
    validateBeanConstraints(requests)

    val commands = requests.mapIndexed { index, request -> request.toCommand(index, monthlyPeriod) }
    val requestedExistingIds = commands.mapNotNull { it.id }

    validateDuplicateIds(commands)

    val existingTransactions = findExistingTransactions(requestedExistingIds.toSet())
    validateExistingTransactionsAreInTargetMonth(
      commands,
      existingTransactions.values,
      monthlyPeriod,
    )

    val targets = resolveTargets(commands)

    transactionRepository.deleteAll(
      monthlySnapshot.filter { it.requiredId() !in requestedExistingIds }
    )

    val savedTransactions = commands.map { command ->
      val transaction =
        command.id?.let { existingTransactions.getValue(it) }
          ?: TransactionEntity(
            type = command.type,
            transactionDate = command.transactionDate,
            amount = command.amount,
          )

      applyCommand(transaction, command, targets)

      if (command.id == null) {
        transactionRepository.save(transaction)
      } else {
        transaction
      }
    }

    return savedTransactions.map { it.toResponse() }
  }

  private fun applyCommand(
    transaction: TransactionEntity,
    command: TransactionMonthlySaveCommand,
    targets: ResolvedTransactionTargets,
  ) {
    if (command.type == TransactionType.TRANSFER) {
      transaction.category = null
      transaction.paymentMethod = null
      transaction.transferSource = targets.transferAccounts.getValue(command.categoryId)
      transaction.transferDestination =
        targets.transferAccounts.getValue(requireNotNull(command.paymentMethodId))
    } else {
      transaction.category = targets.categories.getValue(command.categoryId)
      // 収入は支払い方法を持たない(command.paymentMethodId が null)。
      transaction.paymentMethod =
        command.paymentMethodId?.let { targets.paymentMethods.getValue(it) }
      transaction.transferSource = null
      transaction.transferDestination = null
    }
    transaction.type = command.type
    transaction.transactionDate = command.transactionDate
    transaction.amount = command.amount
    transaction.memo = command.memo
    transaction.displayOrder = command.displayOrder
  }

  private fun saveOne(
    year: Int?,
    month: Int?,
    id: Long?,
    request: TransactionSaveRequest,
  ): TransactionResponse {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
    val existingTransaction = id?.let { findTransactionInMonth(it, monthlyPeriod) }
    val displayOrder =
      existingTransaction?.displayOrder
        ?: ((findMonthlyTransactions(monthlyPeriod).maxOfOrNull { it.displayOrder } ?: -10) + 10)
    val saveRequest = request.toMonthlySaveRequest(id, displayOrder)
    validateBeanConstraints(listOf(saveRequest))

    val command = saveRequest.toCommand(index = 0, monthlyPeriod)
    val targets = resolveTargets(listOf(command))

    val transaction =
      existingTransaction
        ?: TransactionEntity(
          type = command.type,
          transactionDate = command.transactionDate,
          amount = command.amount,
        )
    applyCommand(transaction, command, targets)

    return transactionRepository.save(transaction).toResponse()
  }

  private fun findTransactionInMonth(
    id: Long,
    monthlyPeriod: MonthlyPeriod,
  ): TransactionEntity {
    val transaction =
      transactionRepository.findById(id).orElseThrow {
        ResourceNotFoundException("家計簿データが見つかりません")
      }
    if (!transaction.transactionDate.isIn(monthlyPeriod)) {
      throw ResourceNotFoundException("家計簿データが見つかりません")
    }
    return transaction
  }

  private fun validateBeanConstraints(requests: List<TransactionMonthlySaveRequest>) {
    val errors = requests.flatMapIndexed { index, request ->
      validator.validate(request).map {
        ApiFieldErrorResponse(
          field = rowField(index, it.propertyPath.toString()),
          message = it.message,
        )
      } + request.targetValidationErrors(index)
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("入力内容に誤りがあります", errors)
    }
  }

  private fun TransactionMonthlySaveRequest.targetValidationErrors(
    index: Int
  ): List<ApiFieldErrorResponse> {
    val effectiveType = type ?: TransactionType.EXPENSE
    val positiveCategoryId = categoryId?.takeIf { it > 0 }
    val positivePaymentMethodId = paymentMethodId?.takeIf { it > 0 }
    val targets =
      if (effectiveType == TransactionType.TRANSFER) {
        TransactionTargetIds(
          transferSourceId = positiveCategoryId,
          transferDestinationId = positivePaymentMethodId,
        )
      } else {
        TransactionTargetIds(
          categoryId = positiveCategoryId,
          // 従来どおり、収入で指定された支払い方法は検証せず保存時に無視する。
          paymentMethodId =
            if (effectiveType == TransactionType.INCOME) null else positivePaymentMethodId,
        )
      }

    return TransactionTargetRules.validate(effectiveType, targets).map { violation ->
      ApiFieldErrorResponse(
        field = rowField(index, violation.field.toTransactionRequestField()),
        message = violation.message,
      )
    }
  }

  private fun TransactionTargetField.toTransactionRequestField(): String =
    when (this) {
      TransactionTargetField.CATEGORY,
      TransactionTargetField.TRANSFER_SOURCE -> "categoryId"
      TransactionTargetField.PAYMENT_METHOD,
      TransactionTargetField.TRANSFER_DESTINATION -> "paymentMethodId"
    }

  private fun validateDuplicateIds(commands: List<TransactionMonthlySaveCommand>) {
    val seenIds = mutableSetOf<Long>()
    val errors = commands.mapNotNull { command ->
      command.id
        ?.takeIf { !seenIds.add(it) }
        ?.let {
          ApiFieldErrorResponse(
            field = rowField(command.index, "id"),
            message = "同じ家計簿データIDが重複しています",
          )
        }
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("同じ家計簿データIDが重複しています", errors)
    }
  }

  private fun findExistingTransactions(ids: Set<Long>): Map<Long, TransactionEntity> {
    if (ids.isEmpty()) {
      return emptyMap()
    }

    val transactions = transactionRepository.findAllById(ids).associateBy { it.requiredId() }
    if (transactions.keys != ids) {
      throw ResourceNotFoundException("家計簿データが見つかりません")
    }
    return transactions
  }

  private fun validateExistingTransactionsAreInTargetMonth(
    commands: List<TransactionMonthlySaveCommand>,
    transactions: Collection<TransactionEntity>,
    monthlyPeriod: MonthlyPeriod,
  ) {
    val invalidIds =
      transactions
        .filter { !it.transactionDate.isIn(monthlyPeriod) }
        .map { it.requiredId() }
        .toSet()
    if (invalidIds.isNotEmpty()) {
      throw ApiValidationException(
        message = "対象月以外の家計簿データは更新できません",
        errors =
          commands
            .filter { it.id in invalidIds }
            .map {
              ApiFieldErrorResponse(
                field = rowField(it.index, "id"),
                message = "対象月以外の家計簿データは更新できません",
              )
            },
      )
    }
  }

  private fun findCategories(ids: Set<Long>): Map<Long, CategoryEntity> {
    val categories = categoryRepository.findAllById(ids).associateBy { it.requiredId() }
    if (categories.keys != ids) {
      throw ResourceNotFoundException("カテゴリが見つかりません")
    }
    return categories
  }

  private fun findPaymentMethods(ids: Set<Long>): Map<Long, PaymentMethodEntity> {
    val paymentMethods = paymentMethodRepository.findAllById(ids).associateBy { it.requiredId() }
    if (paymentMethods.keys != ids) {
      throw ResourceNotFoundException("支払い方法が見つかりません")
    }
    return paymentMethods
  }

  private fun findTransferAccounts(ids: Set<Long>): Map<Long, TransferAccountEntity> {
    val transferAccounts =
      transferAccountRepository.findAllById(ids).associateBy { it.requiredId() }
    if (transferAccounts.keys != ids) {
      throw ResourceNotFoundException("振替元・振替先が見つかりません")
    }
    return transferAccounts
  }

  private fun resolveTargets(
    commands: List<TransactionMonthlySaveCommand>
  ): ResolvedTransactionTargets {
    val categories =
      findCategories(
        commands.filterNot { it.type == TransactionType.TRANSFER }.map { it.categoryId }.toSet()
      )
    val paymentMethods =
      findPaymentMethods(
        commands
          .filterNot { it.type == TransactionType.TRANSFER }
          .mapNotNull { it.paymentMethodId }
          .toSet()
      )
    val transferAccounts =
      findTransferAccounts(
        commands
          .filter { it.type == TransactionType.TRANSFER }
          .flatMap { listOfNotNull(it.categoryId, it.paymentMethodId) }
          .toSet()
      )
    validateCategoryTypes(commands, categories)
    return ResolvedTransactionTargets(categories, paymentMethods, transferAccounts)
  }

  private fun validateCategoryTypes(
    commands: List<TransactionMonthlySaveCommand>,
    categories: Map<Long, CategoryEntity>,
  ) {
    val errors =
      commands
        .filterNot { it.type == TransactionType.TRANSFER }
        .filter {
          !TransactionTargetRules.categoryMatches(
            it.type,
            categories.getValue(it.categoryId).type,
          )
        }
        .map {
          ApiFieldErrorResponse(
            field = rowField(it.index, "categoryId"),
            message = TransactionTargetRules.CATEGORY_TYPE_MISMATCH_MESSAGE,
          )
        }
    if (errors.isNotEmpty()) {
      throw ApiValidationException(TransactionTargetRules.CATEGORY_TYPE_MISMATCH_MESSAGE, errors)
    }
  }

  private fun TransactionSaveRequest.toMonthlySaveRequest(
    id: Long?,
    displayOrder: Int,
  ): TransactionMonthlySaveRequest =
    TransactionMonthlySaveRequest(
      id = id,
      date = date,
      type = type,
      categoryId = categoryId,
      paymentMethodId = paymentMethodId,
      amount = amount,
      memo = memo,
      displayOrder = displayOrder,
    )

  private fun TransactionEntity.toMonthlySaveRequest(): TransactionMonthlySaveRequest {
    val categoryId: Long
    val paymentMethodId: Long?

    if (type == TransactionType.TRANSFER) {
      categoryId = (transferSource ?: error("Transfer source is not assigned")).requiredId()
      paymentMethodId =
        (transferDestination ?: error("Transfer destination is not assigned")).requiredId()
    } else {
      categoryId = (category ?: error("Category is not assigned")).requiredId()
      // 収入は支払い方法を持たない。支出のみ必須。
      paymentMethodId =
        if (type == TransactionType.INCOME) {
          null
        } else {
          (paymentMethod ?: error("Payment method is not assigned")).requiredId()
        }
    }

    return TransactionMonthlySaveRequest(
      id = requiredId(),
      date = transactionDate.toString(),
      type = type,
      categoryId = categoryId,
      paymentMethodId = paymentMethodId,
      amount = amount,
      memo = memo,
      displayOrder = displayOrder,
    )
  }

  /** [validateBeanConstraints] を通過済みのリクエストだけを受け取るため、null 許容の項目はすべて確定している。 */
  private fun TransactionMonthlySaveRequest.toCommand(
    index: Int,
    monthlyPeriod: MonthlyPeriod,
  ): TransactionMonthlySaveCommand {
    val transactionDate = parseDate(index, date!!)
    if (!transactionDate.isIn(monthlyPeriod)) {
      throw ApiValidationException(
        message = "URLの年月と日付が一致していません",
        errors =
          listOf(
            ApiFieldErrorResponse(
              field = rowField(index, "date"),
              message = "URLの年月と日付が一致していません",
            )
          ),
      )
    }

    return TransactionMonthlySaveCommand(
      index = index,
      id = id,
      transactionDate = transactionDate,
      type = type!!,
      categoryId = categoryId!!,
      // 収入は支払い方法を持たない。
      paymentMethodId = if (type == TransactionType.INCOME) null else paymentMethodId!!,
      amount = amount!!,
      memo = memo,
      displayOrder = displayOrder!!,
    )
  }

  private fun parseDate(index: Int, date: String): LocalDate =
    runCatching { LocalDate.parse(date) }
      .getOrElse {
        throw ApiValidationException(
          message = "日付の形式が不正です",
          errors =
            listOf(
              ApiFieldErrorResponse(
                field = rowField(index, "date"),
                message = "日付の形式が不正です",
              )
            ),
        )
      }

  private fun TransactionEntity.toResponse(): TransactionResponse {
    val categoryId: Long
    val categoryName: String
    val paymentMethodId: Long?
    val paymentMethodName: String?

    if (type == TransactionType.TRANSFER) {
      val source = transferSource ?: error("Transfer source is not assigned")
      val destination = transferDestination ?: error("Transfer destination is not assigned")
      categoryId = source.requiredId()
      categoryName = source.name
      paymentMethodId = destination.requiredId()
      paymentMethodName = destination.name
    } else {
      val selectedCategory = category ?: error("Category is not assigned")
      categoryId = selectedCategory.requiredId()
      categoryName = selectedCategory.name
      // 収入は支払い方法を持たない(既存データが持っていても null として扱う)。
      if (type == TransactionType.INCOME) {
        paymentMethodId = null
        paymentMethodName = null
      } else {
        val selectedPaymentMethod = paymentMethod ?: error("Payment method is not assigned")
        paymentMethodId = selectedPaymentMethod.requiredId()
        paymentMethodName = selectedPaymentMethod.name
      }
    }

    return TransactionResponse(
      id = requiredId(),
      date = transactionDate.toString(),
      type = type,
      categoryId = categoryId,
      categoryName = categoryName,
      paymentMethodId = paymentMethodId,
      paymentMethodName = paymentMethodName,
      amount = amount,
      memo = memo,
      displayOrder = displayOrder,
    )
  }

  private fun LocalDate.isIn(monthlyPeriod: MonthlyPeriod): Boolean =
    !isBefore(monthlyPeriod.startDate) && isBefore(monthlyPeriod.endDateExclusive)

  private fun findMonthlyTransactions(monthlyPeriod: MonthlyPeriod): List<TransactionEntity> =
    transactionRepository
      .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
        monthlyPeriod.startDate,
        monthlyPeriod.endDateExclusive,
      )

  private fun findTransactionsForExport(
    exportPeriod: TransactionExportPeriod
  ): List<TransactionEntity> =
    if (exportPeriod.isAll) {
      transactionRepository.findAllByOrderByTransactionDateAscDisplayOrderAscIdAsc()
    } else {
      transactionRepository
        .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanEqualOrderByTransactionDateAscDisplayOrderAscIdAsc(
          requireNotNull(exportPeriod.startDate),
          requireNotNull(exportPeriod.endDate),
        )
    }

  private data class TransactionMonthlySaveCommand(
    val index: Int,
    val id: Long?,
    val transactionDate: LocalDate,
    val type: TransactionType,
    val categoryId: Long,
    // 収入は支払い方法を持たないため null。
    val paymentMethodId: Long?,
    val amount: Int,
    val memo: String?,
    val displayOrder: Int,
  )

  private data class ResolvedTransactionTargets(
    val categories: Map<Long, CategoryEntity>,
    val paymentMethods: Map<Long, PaymentMethodEntity>,
    val transferAccounts: Map<Long, TransferAccountEntity>,
  )

  private fun rowField(index: Int, field: String): String = "[$index].$field"
}
