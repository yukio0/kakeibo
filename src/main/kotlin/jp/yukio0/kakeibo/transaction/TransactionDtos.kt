package jp.yukio0.kakeibo.transaction

import jp.yukio0.kakeibo.domain.TransactionType

data class TransactionResponse(
  val id: Long,
  val date: String,
  val type: TransactionType,
  val categoryId: Long,
  val categoryName: String,
  val amount: Int,
  val memo: String?,
  val displayOrder: Int,
)
