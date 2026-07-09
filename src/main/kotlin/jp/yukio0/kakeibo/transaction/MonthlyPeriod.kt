package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import java.time.YearMonth
import jp.yukio0.kakeibo.api.BadRequestException

data class MonthlyPeriod(
  val year: Int,
  val month: Int,
  val startDate: LocalDate,
  val endDateExclusive: LocalDate,
) {
  companion object {
    fun from(year: Int?, month: Int?): MonthlyPeriod {
      if (year == null || month == null || year !in MIN_YEAR..MAX_YEAR || month !in 1..12) {
        throw BadRequestException("年月が不正です")
      }

      val yearMonth = YearMonth.of(year, month)
      return MonthlyPeriod(
        year = yearMonth.year,
        month = yearMonth.monthValue,
        startDate = yearMonth.atDay(1),
        endDateExclusive = yearMonth.plusMonths(1).atDay(1),
      )
    }

    private const val MIN_YEAR = 1
    private const val MAX_YEAR = 9999
  }
}
