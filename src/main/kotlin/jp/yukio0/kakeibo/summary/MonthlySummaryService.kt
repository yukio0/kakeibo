package jp.yukio0.kakeibo.summary

import java.time.YearMonth
import java.time.temporal.ChronoUnit
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

  /** 対象月の日別収支を返す。最初に記録された日が対象月内ならその日から、翌月以降なら月初から月末までを0円の日も含めて返す。 */
  @Transactional(readOnly = true)
  fun getMonthlyDailySummary(year: Int?, month: Int?): DailySummaryResponse {
    val monthlyPeriod = MonthlyPeriod.from(year, month)
    val firstTransactionDate = transactionRepository.findEarliestTransactionDate()
    if (
      firstTransactionDate == null || !firstTransactionDate.isBefore(monthlyPeriod.endDateExclusive)
    ) {
      return DailySummaryResponse(
        year = monthlyPeriod.year,
        month = monthlyPeriod.month,
        days = emptyList(),
      )
    }

    val startDate = maxOf(firstTransactionDate, monthlyPeriod.startDate)
    val totalsByDate =
      transactionRepository
        .sumAmountsByDateAndTypeForPeriod(startDate, monthlyPeriod.endDateExclusive)
        .groupBy { it.transactionDate }
        .mapValues { (_, totals) -> totals.associate { it.type to it.total } }

    val days =
      generateSequence(startDate) { date ->
          date.plusDays(1).takeIf { it.isBefore(monthlyPeriod.endDateExclusive) }
        }
        .map { date ->
          val totalsByType = totalsByDate[date].orEmpty()
          DailySummaryItem(
            date = date,
            incomeTotal = totalsByType[TransactionType.INCOME] ?: 0L,
            expenseTotal = totalsByType[TransactionType.EXPENSE] ?: 0L,
          )
        }
        .toList()

    return DailySummaryResponse(
      year = monthlyPeriod.year,
      month = monthlyPeriod.month,
      days = days,
    )
  }

  /**
   * アンカー月(year/month)を末尾に、最初に記録がある月からアンカー月までの月次サマリを古い順で返す。 対象は最大 [months]
   * か月(既定/上限は直近12か月)で、途中でデータがない月は0円としてゼロ埋めする。
   */
  @Transactional(readOnly = true)
  fun getMonthlyTrend(year: Int?, month: Int?, months: Int?): MonthlyTrendResponse {
    val anchor = MonthlyPeriod.from(year, month)
    val maxCount = (months ?: DEFAULT_TREND_MONTHS).coerceIn(1, MAX_TREND_MONTHS)
    val anchorYearMonth = YearMonth.of(anchor.year, anchor.month)

    val firstYearMonth =
      transactionRepository.findEarliestTransactionDate()?.let { YearMonth.from(it) }
    val count =
      if (firstYearMonth == null || firstYearMonth.isAfter(anchorYearMonth)) {
        1
      } else {
        (ChronoUnit.MONTHS.between(firstYearMonth, anchorYearMonth) + 1)
          .coerceIn(1L, maxCount.toLong())
          .toInt()
      }

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
    const val DEFAULT_TREND_MONTHS = 12
    const val MAX_TREND_MONTHS = 24
  }
}
