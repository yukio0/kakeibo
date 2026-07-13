package jp.yukio0.kakeibo.summary

import java.time.YearMonth
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.transaction.MonthlyPeriod
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MonthlySummaryService(private val transactionRepository: TransactionRepository) {

  @Transactional(readOnly = true)
  fun getMonthlySummary(year: Int?, month: Int?): MonthlySummaryResponse {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
    val totalsByType =
      transactionRepository
        .sumAmountsByTypeForPeriod(
          monthlyPeriod.startDate,
          monthlyPeriod.endDateExclusive,
        )
        .associate { it.type to it.total }

    val incomeTotal = totalsByType[TransactionType.INCOME] ?: 0L
    val expenseTotal = totalsByType[TransactionType.EXPENSE] ?: 0L
    return MonthlySummaryResponse(
      year = monthlyPeriod.year,
      month = monthlyPeriod.month,
      incomeTotal = incomeTotal,
      expenseTotal = expenseTotal,
      balance = incomeTotal - expenseTotal,
    )
  }

  /** アンカー月(year/month)を末尾に、直近 [months] か月分の月次サマリを古い順で返す。 */
  @Transactional(readOnly = true)
  fun getMonthlyTrend(year: Int?, month: Int?, months: Int?): MonthlyTrendResponse {
    val anchor = MonthlyPeriod.from(year, month)
    val count = (months ?: DEFAULT_TREND_MONTHS).coerceIn(1, MAX_TREND_MONTHS)
    val anchorYearMonth = YearMonth.of(anchor.year, anchor.month)

    val summaries =
      (count - 1 downTo 0).map { monthsBack ->
        val target = anchorYearMonth.minusMonths(monthsBack.toLong())
        getMonthlySummary(target.year, target.monthValue)
      }

    return MonthlyTrendResponse(months = summaries)
  }

  @Transactional(readOnly = true)
  fun getMonthlyCategoryExpenses(year: Int?, month: Int?): CategoryExpenseSummaryResponse {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
    val items =
      transactionRepository
        .sumExpenseAmountsByCategoryForPeriod(
          monthlyPeriod.startDate,
          monthlyPeriod.endDateExclusive,
        )
        .map { CategoryExpenseItem(it.categoryId, it.categoryName, it.total) }

    return CategoryExpenseSummaryResponse(
      year = monthlyPeriod.year,
      month = monthlyPeriod.month,
      expenseTotal = items.sumOf { it.total },
      categories = items,
    )
  }

  private companion object {
    const val DEFAULT_TREND_MONTHS = 6
    const val MAX_TREND_MONTHS = 24
  }
}
