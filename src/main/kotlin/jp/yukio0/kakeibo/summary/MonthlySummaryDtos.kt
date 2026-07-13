package jp.yukio0.kakeibo.summary

data class MonthlySummaryResponse(
  val year: Int,
  val month: Int,
  val incomeTotal: Long,
  val expenseTotal: Long,
  val balance: Long,
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
