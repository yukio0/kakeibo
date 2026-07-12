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
}
