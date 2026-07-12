package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.api.BadRequestException

data class TransactionExportPeriod(
  val startDate: LocalDate? = null,
  val endDate: LocalDate? = null,
) {
  val isAll: Boolean
    get() = startDate == null

  fun fileName(): String =
    if (isAll) {
      "kakeibo-all.csv"
    } else {
      "kakeibo-${requireNotNull(startDate)}-${requireNotNull(endDate)}.csv"
    }

  companion object {
    fun from(startDate: LocalDate?, endDate: LocalDate?): TransactionExportPeriod {
      if (startDate == null && endDate == null) {
        return TransactionExportPeriod()
      }
      if (startDate == null || endDate == null) {
        throw BadRequestException("開始日と終了日を両方指定してください")
      }
      if (endDate.isBefore(startDate)) {
        throw BadRequestException("終了日は開始日以降を指定してください")
      }
      return TransactionExportPeriod(startDate = startDate, endDate = endDate)
    }
  }
}
