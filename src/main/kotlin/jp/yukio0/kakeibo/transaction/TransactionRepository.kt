package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface TransactionRepository : JpaRepository<TransactionEntity, Long> {
  @EntityGraph(attributePaths = ["category"])
  fun findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
    startDate: LocalDate,
    endDateExclusive: LocalDate,
  ): List<TransactionEntity>

  fun existsByCategoryId(categoryId: Long): Boolean
}
