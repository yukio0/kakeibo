package jp.yukio0.kakeibo.transaction

import java.time.YearMonth
import jp.yukio0.kakeibo.api.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(private val transactionRepository: TransactionRepository) {

  @Transactional(readOnly = true)
  fun findMonthly(year: Int?, month: Int?): List<TransactionResponse> {
    val targetMonth = validateYearMonth(year, month)
    val startDate = targetMonth.atDay(1)
    val endDateExclusive = targetMonth.plusMonths(1).atDay(1)

    return transactionRepository
      .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
        startDate,
        endDateExclusive,
      )
      .map { it.toResponse() }
  }

  private fun validateYearMonth(year: Int?, month: Int?): YearMonth {
    if (year == null || month == null || year !in MIN_YEAR..MAX_YEAR || month !in 1..12) {
      throw BadRequestException("年月が不正です")
    }
    return YearMonth.of(year, month)
  }

  private fun TransactionEntity.toResponse(): TransactionResponse {
    val categoryId = category.id ?: error("Category id is not assigned")
    return TransactionResponse(
      id = id ?: error("Transaction id is not assigned"),
      date = transactionDate.toString(),
      type = type,
      categoryId = categoryId,
      categoryName = category.name,
      amount = amount,
      memo = memo,
      displayOrder = displayOrder,
    )
  }

  private companion object {
    const val MIN_YEAR = 1
    const val MAX_YEAR = 9999
  }
}
