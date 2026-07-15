package jp.yukio0.kakeibo.recurring

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.persistence.AuditableEntity
import jp.yukio0.kakeibo.transfer.TransferAccountEntity

@Entity
@Table(name = "recurring_transaction_templates")
class RecurringTransactionTemplateEntity(
  @field:Column(nullable = false, length = 100) var name: String,
  @field:Column(nullable = false) var enabled: Boolean,
  @field:Column(name = "day_of_month", nullable = false) var dayOfMonth: Int,
  @field:Enumerated(EnumType.STRING)
  @field:Column(nullable = false, length = 20)
  var type: TransactionType,
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "category_id")
  var category: CategoryEntity? = null,
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "payment_method_id")
  var paymentMethod: PaymentMethodEntity? = null,
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "transfer_source_id")
  var transferSource: TransferAccountEntity? = null,
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "transfer_destination_id")
  var transferDestination: TransferAccountEntity? = null,
  @field:Column(name = "default_amount") var defaultAmount: Int? = null,
  @field:Column(length = 500) var memo: String? = null,
  @field:Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
) : AuditableEntity()
