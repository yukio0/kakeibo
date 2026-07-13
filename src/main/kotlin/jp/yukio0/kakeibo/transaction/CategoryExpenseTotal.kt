package jp.yukio0.kakeibo.transaction

/** カテゴリ別の支出集計。JPQL の集約クエリからそのまま生成される投影。 */
data class CategoryExpenseTotal(
  val categoryId: Long,
  val categoryName: String,
  val total: Long,
)
