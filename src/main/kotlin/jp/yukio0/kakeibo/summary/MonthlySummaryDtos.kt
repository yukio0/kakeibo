package jp.yukio0.kakeibo.summary

import java.time.LocalDate

data class MonthlySummaryResponse(
  val year: Int,
  val month: Int,
  val incomeTotal: Long,
  val expenseTotal: Long,
  val balance: Long,
)

data class MonthlyTrendResponse(val months: List<MonthlySummaryResponse>)

data class DailySummaryResponse(
  val year: Int,
  val month: Int,
  val days: List<DailySummaryItem>,
)

data class DailySummaryItem(
  val date: LocalDate,
  val incomeTotal: Long,
  val expenseTotal: Long,
)

data class CategoryExpenseSummaryResponse(
  val year: Int,
  val month: Int,
  val expenseTotal: Long,
  val categories: List<CategoryExpenseItem>,
)

data class CategoryExpenseItem(
  val categoryId: Long,
  val categoryName: String,
  val total: Long,
)
