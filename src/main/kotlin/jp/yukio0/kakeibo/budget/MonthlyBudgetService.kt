package jp.yukio0.kakeibo.budget

import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.transaction.MonthlyPeriod
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MonthlyBudgetService(
  private val monthlyBudgetRepository: MonthlyBudgetRepository,
  private val categoryRepository: CategoryRepository,
  private val transactionRepository: TransactionRepository,
) {

  @Transactional(readOnly = true)
  fun getMonthlyBudget(year: Int?, month: Int?): MonthlyBudgetResponse {
    val period = MonthlyPeriod.from(year, month)
    return buildResponse(period.year, period.month)
  }

  @Transactional
  fun updateMonthlyBudget(request: MonthlyBudgetUpdateRequest): MonthlyBudgetResponse {
    val period = MonthlyPeriod.from(request.year, request.month)
    val requestedCategoryBudgets = checkNotNull(request.categoryBudgets)
    val requestedCategoryIds = requestedCategoryBudgets.map { checkNotNull(it.categoryId) }
    validateNoDuplicateCategories(requestedCategoryIds)

    val categoriesById =
      categoryRepository.findAllById(requestedCategoryIds).associateBy {
        it.requiredId()
      }
    val validatedCategories = requestedCategoryBudgets.mapIndexed { index, categoryBudget ->
      val categoryId = checkNotNull(categoryBudget.categoryId)
      val category =
        categoriesById[categoryId]
          ?: validationError(
            field = "categoryBudgets[$index].categoryId",
            message = "カテゴリが見つかりません",
          )
      if (category.type != TransactionType.EXPENSE) {
        validationError(
          field = "categoryBudgets[$index].categoryId",
          message = "支出カテゴリを指定してください",
        )
      }
      ValidatedCategoryBudget(category, checkNotNull(categoryBudget.amount))
    }

    val existing = monthlyBudgetRepository.findByBudgetYearAndBudgetMonth(period.year, period.month)
    if (request.overallBudget == null && validatedCategories.isEmpty()) {
      existing?.let(monthlyBudgetRepository::delete)
      return buildResponse(period.year, period.month)
    }

    val monthlyBudget =
      existing
        ?: MonthlyBudgetEntity(
          budgetYear = period.year,
          budgetMonth = period.month,
        )
    monthlyBudget.overallBudget = request.overallBudget
    replaceCategoryBudgets(monthlyBudget, validatedCategories)
    monthlyBudgetRepository.save(monthlyBudget)

    return buildResponse(period.year, period.month)
  }

  private fun replaceCategoryBudgets(
    monthlyBudget: MonthlyBudgetEntity,
    requested: List<ValidatedCategoryBudget>,
  ) {
    val requestedByCategoryId = requested.associateBy { it.category.requiredId() }
    monthlyBudget.categoryBudgets.removeIf {
      it.category.requiredId() !in requestedByCategoryId
    }

    val existingByCategoryId =
      monthlyBudget.categoryBudgets.associateBy {
        it.category.requiredId()
      }
    requested.forEach { budget ->
      val existing = existingByCategoryId[budget.category.requiredId()]
      if (existing == null) {
        monthlyBudget.addCategoryBudget(budget.category, budget.amount)
      } else {
        existing.amount = budget.amount
      }
    }
  }

  private fun validateNoDuplicateCategories(categoryIds: List<Long>) {
    val duplicateId =
      categoryIds.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    if (duplicateId != null) {
      val duplicateIndex = categoryIds.indexOfLast { it == duplicateId }
      validationError(
        field = "categoryBudgets[$duplicateIndex].categoryId",
        message = "同じカテゴリが重複しています",
      )
    }
  }

  private fun validationError(field: String, message: String): Nothing {
    throw ApiValidationException(
      message = "入力内容に誤りがあります",
      errors = listOf(ApiFieldErrorResponse(field = field, message = message)),
    )
  }

  private fun buildResponse(year: Int, month: Int): MonthlyBudgetResponse {
    val period = MonthlyPeriod.from(year, month)
    val monthlyBudget =
      monthlyBudgetRepository.findByBudgetYearAndBudgetMonth(period.year, period.month)
    val budgetAmountsByCategoryId =
      monthlyBudget?.categoryBudgets.orEmpty().associate {
        it.category.requiredId() to it.amount
      }
    val spentAmountsByCategoryId =
      transactionRepository
        .sumExpenseAmountsByCategoryForPeriod(period.startDate, period.endDateExclusive)
        .associate { it.categoryId to it.total }
    val spentAmount =
      transactionRepository
        .sumAmountsByTypeForPeriod(period.startDate, period.endDateExclusive)
        .firstOrNull { it.type == TransactionType.EXPENSE }
        ?.total ?: 0L
    val categories =
      categoryRepository.findAllByTypeOrderByDisplayOrderAscIdAsc(TransactionType.EXPENSE).map {
        category ->
        val categoryId = category.requiredId()
        val budgetAmount = budgetAmountsByCategoryId[categoryId]
        val spentAmount = spentAmountsByCategoryId[categoryId] ?: 0L
        CategoryBudgetResponse(
          categoryId = categoryId,
          categoryName = category.name,
          budgetAmount = budgetAmount,
          spentAmount = spentAmount,
          remainingAmount = remainingAmount(budgetAmount, spentAmount),
          overAmount = overAmount(budgetAmount, spentAmount),
        )
      }
    val overallBudget = monthlyBudget?.overallBudget

    return MonthlyBudgetResponse(
      year = period.year,
      month = period.month,
      overallBudget = overallBudget,
      spentAmount = spentAmount,
      remainingAmount = remainingAmount(overallBudget, spentAmount),
      overAmount = overAmount(overallBudget, spentAmount),
      categories = categories,
    )
  }

  private fun remainingAmount(budgetAmount: Int?, spentAmount: Long): Long? = budgetAmount?.let {
    (it.toLong() - spentAmount).coerceAtLeast(0)
  }

  private fun overAmount(budgetAmount: Int?, spentAmount: Long): Long? = budgetAmount?.let {
    (spentAmount - it.toLong()).coerceAtLeast(0)
  }

  private data class ValidatedCategoryBudget(
    val category: CategoryEntity,
    val amount: Int,
  )
}
