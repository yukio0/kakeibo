package jp.yukio0.kakeibo.recurring

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RecurringTransactionTemplateRepository :
  JpaRepository<RecurringTransactionTemplateEntity, Long> {

  @EntityGraph(
    attributePaths = ["category", "paymentMethod", "transferSource", "transferDestination"]
  )
  fun findAllByOrderByDisplayOrderAscIdAsc(): List<RecurringTransactionTemplateEntity>

  @EntityGraph(
    attributePaths = ["category", "paymentMethod", "transferSource", "transferDestination"]
  )
  fun findAllByEnabledTrueOrderByDisplayOrderAscIdAsc(): List<RecurringTransactionTemplateEntity>

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM RecurringTransactionTemplateEntity t WHERE t.id IN :ids ORDER BY t.id")
  fun findAllByIdsForUpdate(
    @Param("ids") ids: Collection<Long>
  ): List<RecurringTransactionTemplateEntity>

  fun existsByCategoryId(categoryId: Long): Boolean

  fun existsByPaymentMethodId(paymentMethodId: Long): Boolean

  fun existsByTransferSourceIdOrTransferDestinationId(
    transferSourceId: Long,
    transferDestinationId: Long,
  ): Boolean
}

interface RecurringTransactionRegistrationRepository :
  JpaRepository<RecurringTransactionRegistrationEntity, Long> {

  @EntityGraph(
    attributePaths =
      [
        "template",
        "transaction",
        "transaction.category",
        "transaction.paymentMethod",
        "transaction.transferSource",
        "transaction.transferDestination",
      ]
  )
  fun findAllByTemplateIdInAndTargetYearAndTargetMonth(
    templateIds: Collection<Long>,
    targetYear: Int,
    targetMonth: Int,
  ): List<RecurringTransactionRegistrationEntity>
}
