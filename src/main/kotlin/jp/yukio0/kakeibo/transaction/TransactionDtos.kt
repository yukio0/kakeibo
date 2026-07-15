package jp.yukio0.kakeibo.transaction

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import jp.yukio0.kakeibo.domain.TransactionType

data class TransactionSaveRequest(
  @field:NotBlank(message = "日付を入力してください") val date: String?,
  @field:NotNull(message = "種別を選択してください") val type: TransactionType?,
  val categoryId: Long?,
  val paymentMethodId: Long?,
  @field:NotNull(message = "金額を入力してください")
  @field:Min(value = 1, message = "金額は1以上で入力してください")
  val amount: Int?,
  @field:Size(max = 500, message = "メモは500文字以内で入力してください") val memo: String?,
)

data class TransactionMonthlySaveRequest(
  @field:Positive(message = "IDは正の整数で入力してください") val id: Long?,
  @field:NotBlank(message = "日付を入力してください") val date: String?,
  @field:NotNull(message = "種別を選択してください") val type: TransactionType?,
  val categoryId: Long?,
  val paymentMethodId: Long?,
  @field:NotNull(message = "金額を入力してください")
  @field:Min(value = 1, message = "金額は1以上で入力してください")
  val amount: Int?,
  @field:Size(max = 500, message = "メモは500文字以内で入力してください") val memo: String?,
  @field:NotNull(message = "表示順を入力してください")
  @field:Min(value = 0, message = "表示順は0以上で入力してください")
  val displayOrder: Int?,
)

data class TransactionResponse(
  val id: Long,
  val date: String,
  val type: TransactionType,
  val categoryId: Long,
  val categoryName: String,
  // 収入は支払い方法を持たないため null になり得る。
  val paymentMethodId: Long?,
  val paymentMethodName: String?,
  val amount: Int,
  val memo: String?,
  val displayOrder: Int,
)
