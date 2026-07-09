package jp.yukio0.kakeibo.summary

data class MonthlySummaryResponse(
  val year: Int,
  val month: Int,
  val incomeTotal: Long,
  val expenseTotal: Long,
  val balance: Long,
)
