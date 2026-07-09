package jp.yukio0.kakeibo.transaction

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(private val transactionRepository: TransactionRepository) {

  @Transactional(readOnly = true)
  fun findMonthly(year: Int?, month: Int?): List<TransactionResponse> {
    val monthlyPeriod = MonthlyPeriod.from(year, month)

    return transactionRepository
      .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
        monthlyPeriod.startDate,
        monthlyPeriod.endDateExclusive,
      )
      .map { it.toResponse() }
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
}
