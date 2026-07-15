package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.domain.TransactionType

/** 日別・種別ごとの収支集計。JPQL の集約クエリからそのまま生成される投影。 */
data class TransactionDateTypeTotal(
  val transactionDate: LocalDate,
  val type: TransactionType,
  val total: Long,
)
