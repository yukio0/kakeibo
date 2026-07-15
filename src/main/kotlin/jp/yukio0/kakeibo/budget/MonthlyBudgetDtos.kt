package jp.yukio0.kakeibo.budget

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

data class MonthlyBudgetUpdateRequest(
  @field:NotNull(message = "年を入力してください")
  @field:Min(value = 1, message = "年は1以上で入力してください")
  @field:Max(value = 9999, message = "年は9999以下で入力してください")
  val year: Int?,
  @field:NotNull(message = "月を入力してください")
  @field:Min(value = 1, message = "月は1以上で入力してください")
  @field:Max(value = 12, message = "月は12以下で入力してください")
  val month: Int?,
  @field:Min(value = 1, message = "月全体の予算は1円以上で入力してください") val overallBudget: Int?,
  @field:Valid
  @field:NotNull(message = "カテゴリ予算を指定してください")
  val categoryBudgets: List<CategoryBudgetUpdateRequest>?,
)

data class CategoryBudgetUpdateRequest(
  @field:NotNull(message = "カテゴリを選択してください")
  @field:Positive(message = "カテゴリIDは正の整数で入力してください")
  val categoryId: Long?,
  @field:NotNull(message = "カテゴリ予算を入力してください")
  @field:Min(value = 1, message = "カテゴリ予算は1円以上で入力してください")
  val amount: Int?,
)

data class MonthlyBudgetResponse(
  val year: Int,
  val month: Int,
  val overallBudget: Int?,
  val spentAmount: Long,
  val remainingAmount: Long?,
  val overAmount: Long?,
  val categories: List<CategoryBudgetResponse>,
)

data class CategoryBudgetResponse(
  val categoryId: Long,
  val categoryName: String,
  val budgetAmount: Int?,
  val spentAmount: Long,
  val remainingAmount: Long?,
  val overAmount: Long?,
)
