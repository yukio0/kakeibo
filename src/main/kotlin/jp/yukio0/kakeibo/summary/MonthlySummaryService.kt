package jp.yukio0.kakeibo.summary

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
}
