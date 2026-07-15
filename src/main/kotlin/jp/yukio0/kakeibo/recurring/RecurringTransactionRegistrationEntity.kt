package jp.yukio0.kakeibo.recurring

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jp.yukio0.kakeibo.persistence.AuditableEntity
import jp.yukio0.kakeibo.transaction.TransactionEntity

@Entity
@Table(name = "recurring_transaction_registrations")
class RecurringTransactionRegistrationEntity(
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "template_id", nullable = false)
  val template: RecurringTransactionTemplateEntity,
  @field:Column(name = "target_year", nullable = false) val targetYear: Int,
  @field:Column(name = "target_month", nullable = false) val targetMonth: Int,
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "transaction_id", nullable = false)
  val transaction: TransactionEntity,
) : AuditableEntity()
