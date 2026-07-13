package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TransactionRepository : JpaRepository<TransactionEntity, Long> {
  @EntityGraph(
    attributePaths = ["category", "paymentMethod", "transferSource", "transferDestination"]
  )
  fun findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
    startDate: LocalDate,
    endDateExclusive: LocalDate,
  ): List<TransactionEntity>

  @EntityGraph(
    attributePaths = ["category", "paymentMethod", "transferSource", "transferDestination"]
  )
  fun findAllByOrderByTransactionDateAscDisplayOrderAscIdAsc(): List<TransactionEntity>

  @EntityGraph(
    attributePaths = ["category", "paymentMethod", "transferSource", "transferDestination"]
  )
  fun findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanEqualOrderByTransactionDateAscDisplayOrderAscIdAsc(
    startDate: LocalDate,
    endDate: LocalDate,
  ): List<TransactionEntity>

  fun countByTransactionDateGreaterThanEqualAndTransactionDateLessThan(
    startDate: LocalDate,
    endDateExclusive: LocalDate,
  ): Long

  fun existsByCategoryId(categoryId: Long): Boolean

  fun existsByPaymentMethodId(paymentMethodId: Long): Boolean

  fun existsByTransferSourceIdOrTransferDestinationId(
    transferSourceId: Long,
    transferDestinationId: Long,
  ): Boolean

  @Query(
    """
    SELECT new jp.yukio0.kakeibo.transaction.TransactionTypeTotal(t.type, SUM(t.amount))
    FROM TransactionEntity t
    WHERE t.transactionDate >= :startDate
      AND t.transactionDate < :endDateExclusive
    GROUP BY t.type
    """
  )
  fun sumAmountsByTypeForPeriod(
    @Param("startDate") startDate: LocalDate,
    @Param("endDateExclusive") endDateExclusive: LocalDate,
  ): List<TransactionTypeTotal>

  @Query(
    """
    SELECT new jp.yukio0.kakeibo.transaction.CategoryExpenseTotal(c.id, c.name, SUM(t.amount))
    FROM TransactionEntity t
      JOIN t.category c
    WHERE t.transactionDate >= :startDate
      AND t.transactionDate < :endDateExclusive
      AND t.type = jp.yukio0.kakeibo.domain.TransactionType.EXPENSE
    GROUP BY c.id, c.name
    ORDER BY SUM(t.amount) DESC, c.id ASC
    """
  )
  fun sumExpenseAmountsByCategoryForPeriod(
    @Param("startDate") startDate: LocalDate,
    @Param("endDateExclusive") endDateExclusive: LocalDate,
  ): List<CategoryExpenseTotal>
}
