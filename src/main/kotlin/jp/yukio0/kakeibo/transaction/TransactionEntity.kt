package jp.yukio0.kakeibo.transaction

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.persistence.AuditableEntity
import jp.yukio0.kakeibo.transfer.TransferAccountEntity

@Entity
@Table(name = "transactions")
class TransactionEntity(
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
  @field:Enumerated(EnumType.STRING)
  @field:Column(nullable = false, length = 20)
  var type: TransactionType,
  @field:Column(name = "transaction_date", nullable = false) var transactionDate: LocalDate,
  @field:Column(nullable = false) var amount: Int,
  @field:Column(length = 500) var memo: String? = null,
  @field:Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
) : AuditableEntity() {

  @field:Id
  @field:GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: Long? = null
    protected set
}
