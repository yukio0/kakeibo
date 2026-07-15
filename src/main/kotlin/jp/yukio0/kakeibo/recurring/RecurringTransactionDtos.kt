package jp.yukio0.kakeibo.recurring

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.transaction.TransactionResponse

data class RecurringTransactionTemplateRequest(
  @field:NotBlank(message = "テンプレート名を入力してください")
  @field:Size(max = 100, message = "テンプレート名は100文字以内で入力してください")
  val name: String?,
  @field:NotNull(message = "有効・停止を選択してください") val enabled: Boolean?,
  @field:NotNull(message = "毎月の日を入力してください")
  @field:Min(value = 1, message = "毎月の日は1以上で入力してください")
  @field:Max(value = 31, message = "毎月の日は31以下で入力してください")
  val dayOfMonth: Int?,
  @field:NotNull(message = "種別を選択してください") val type: TransactionType?,
  @field:Positive(message = "カテゴリIDは正の整数で入力してください") val categoryId: Long?,
  @field:Positive(message = "支払い方法IDは正の整数で入力してください") val paymentMethodId: Long?,
  @field:Positive(message = "振替元IDは正の整数で入力してください") val transferSourceId: Long?,
  @field:Positive(message = "振替先IDは正の整数で入力してください") val transferDestinationId: Long?,
  @field:Positive(message = "標準金額は1以上で入力してください") val defaultAmount: Int?,
  @field:Size(max = 500, message = "メモは500文字以内で入力してください") val memo: String?,
  @field:NotNull(message = "表示順を入力してください")
  @field:Min(value = 0, message = "表示順は0以上で入力してください")
  val displayOrder: Int?,
)

data class RecurringTransactionTemplateResponse(
  val id: Long,
  val name: String,
  val enabled: Boolean,
  val dayOfMonth: Int,
  val type: TransactionType,
  val categoryId: Long?,
  val categoryName: String?,
  val paymentMethodId: Long?,
  val paymentMethodName: String?,
  val transferSourceId: Long?,
  val transferSourceName: String?,
  val transferDestinationId: Long?,
  val transferDestinationName: String?,
  val defaultAmount: Int?,
  val memo: String?,
  val displayOrder: Int,
)

data class RecurringTransactionCandidatesResponse(
  val year: Int,
  val month: Int,
  val items: List<RecurringTransactionCandidateResponse>,
)

data class RecurringTransactionCandidateResponse(
  val templateId: Long,
  val templateName: String,
  val registered: Boolean,
  val transactionId: Long?,
  val date: String,
  val type: TransactionType,
  val categoryId: Long?,
  val categoryName: String?,
  val paymentMethodId: Long?,
  val paymentMethodName: String?,
  val transferSourceId: Long?,
  val transferSourceName: String?,
  val transferDestinationId: Long?,
  val transferDestinationName: String?,
  val amount: Int?,
  val memo: String?,
)

data class RecurringTransactionRegisterRequest(
  @field:NotNull(message = "対象年を入力してください")
  @field:Min(value = 1, message = "対象年は1以上で入力してください")
  @field:Max(value = 9999, message = "対象年は9999以下で入力してください")
  val year: Int?,
  @field:NotNull(message = "対象月を入力してください")
  @field:Min(value = 1, message = "対象月は1以上で入力してください")
  @field:Max(value = 12, message = "対象月は12以下で入力してください")
  val month: Int?,
  @field:NotNull(message = "登録する定期取引を選択してください")
  @field:Size(min = 1, message = "登録する定期取引を選択してください")
  @field:Valid
  val items: List<RecurringTransactionRegisterItem>?,
)

data class RecurringTransactionRegisterItem(
  @field:NotNull(message = "テンプレートIDを入力してください")
  @field:Positive(message = "テンプレートIDは正の整数で入力してください")
  val templateId: Long?,
  @field:NotBlank(message = "日付を入力してください") val date: String?,
  @field:NotNull(message = "種別を選択してください") val type: TransactionType?,
  @field:Positive(message = "カテゴリIDは正の整数で入力してください") val categoryId: Long?,
  @field:Positive(message = "支払い方法IDは正の整数で入力してください") val paymentMethodId: Long?,
  @field:Positive(message = "振替元IDは正の整数で入力してください") val transferSourceId: Long?,
  @field:Positive(message = "振替先IDは正の整数で入力してください") val transferDestinationId: Long?,
  @field:NotNull(message = "金額を入力してください")
  @field:Positive(message = "金額は1以上で入力してください")
  val amount: Int?,
  @field:Size(max = 500, message = "メモは500文字以内で入力してください") val memo: String?,
)

data class RecurringTransactionRegisterResponse(
  val created: List<TransactionResponse>,
  val skippedTemplateIds: List<Long>,
)
