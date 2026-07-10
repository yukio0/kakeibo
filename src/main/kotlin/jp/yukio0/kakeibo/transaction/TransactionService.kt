package jp.yukio0.kakeibo.transaction

import jakarta.validation.Validator
import java.time.LocalDate
import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.ResourceNotFoundException
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(
  private val transactionRepository: TransactionRepository,
  private val categoryRepository: CategoryRepository,
  private val validator: Validator,
) {

  @Transactional(readOnly = true)
  fun findMonthly(year: Int?, month: Int?): List<TransactionResponse> {
    val monthlyPeriod = MonthlyPeriod.from(year, month)

    return transactionRepository
      .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
        monthlyPeriod.startDate,
        monthlyPeriod.endDateExclusive,
      )
      .map { it.toResponse() }
  }

  @Transactional
  fun saveMonthly(
    year: Int?,
    month: Int?,
    requests: List<TransactionMonthlySaveRequest>,
  ): TransactionMonthlySaveResponse {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
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

    val categories = findCategories(commands.map { it.categoryId }.toSet())
    validateCategoryTypes(commands, categories)

    val monthlyTransactions =
      transactionRepository
        .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
          monthlyPeriod.startDate,
          monthlyPeriod.endDateExclusive,
        )

    transactionRepository.deleteAll(
      monthlyTransactions.filter { it.requiredId() !in requestedExistingIds }
    )

    commands.forEach { command ->
      val category = categories.getValue(command.categoryId)
      val transaction =
        command.id?.let { existingTransactions.getValue(it) }
          ?: TransactionEntity(
            category = category,
            type = command.type,
            transactionDate = command.transactionDate,
            amount = command.amount,
          )

      transaction.category = category
      transaction.type = command.type
      transaction.transactionDate = command.transactionDate
      transaction.amount = command.amount
      transaction.memo = command.memo
      transaction.displayOrder = command.displayOrder

      if (command.id == null) {
        transactionRepository.save(transaction)
      }
    }

    return TransactionMonthlySaveResponse(status = "ok")
  }

  private fun validateBeanConstraints(requests: List<TransactionMonthlySaveRequest>) {
    val errors = requests.flatMapIndexed { index, request ->
      validator.validate(request).map {
        ApiFieldErrorResponse(
          field = rowField(index, it.propertyPath.toString()),
          message = it.message,
        )
      }
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("入力内容に誤りがあります", errors)
    }
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

  private fun validateCategoryTypes(
    commands: List<TransactionMonthlySaveCommand>,
    categories: Map<Long, CategoryEntity>,
  ) {
    val errors =
      commands
        .filter { categories.getValue(it.categoryId).type != it.type }
        .map {
          ApiFieldErrorResponse(
            field = rowField(it.index, "categoryId"),
            message = "種別に合うカテゴリを選択してください",
          )
        }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("種別に合うカテゴリを選択してください", errors)
    }
  }

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
    val categoryId = category.id ?: error("Category id is not assigned")
    return TransactionResponse(
      id = id ?: error("Transaction id is not assigned"),
      date = transactionDate.toString(),
      type = type,
      categoryId = categoryId,
      categoryName = category.name,
      amount = amount,
      memo = memo,
      displayOrder = displayOrder,
    )
  }

  private fun LocalDate.isIn(monthlyPeriod: MonthlyPeriod): Boolean =
    !isBefore(monthlyPeriod.startDate) && isBefore(monthlyPeriod.endDateExclusive)

  private fun CategoryEntity.requiredId(): Long = id ?: error("Category id is not assigned")

  private fun TransactionEntity.requiredId(): Long = id ?: error("Transaction id is not assigned")

  private data class TransactionMonthlySaveCommand(
    val index: Int,
    val id: Long?,
    val transactionDate: LocalDate,
    val type: TransactionType,
    val categoryId: Long,
    val amount: Int,
    val memo: String?,
    val displayOrder: Int,
  )

  private fun rowField(index: Int, field: String): String = "[$index].$field"
}
