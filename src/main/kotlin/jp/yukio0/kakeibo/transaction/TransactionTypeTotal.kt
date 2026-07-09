package jp.yukio0.kakeibo.transaction

import jp.yukio0.kakeibo.domain.TransactionType

data class TransactionTypeTotal(
  val type: TransactionType,
  val total: Long,
)
