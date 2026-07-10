package jp.yukio0.kakeibo.category

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.master.MasterRequest

data class CategoryRequest(
  @field:NotBlank(message = "カテゴリ名を入力してください")
  @field:Size(max = 100, message = "カテゴリ名は100文字以内で入力してください")
  override val name: String?,
  @field:NotNull(message = "種別を選択してください") val type: TransactionType?,
  @field:NotNull(message = "表示順を入力してください")
  @field:Min(value = 0, message = "表示順は0以上で入力してください")
  override val displayOrder: Int?,
) : MasterRequest

data class CategoryResponse(
  val id: Long,
  val name: String,
  val type: TransactionType,
  val displayOrder: Int,
)
