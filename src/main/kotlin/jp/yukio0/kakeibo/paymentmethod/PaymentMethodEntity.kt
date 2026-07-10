package jp.yukio0.kakeibo.paymentmethod

import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jp.yukio0.kakeibo.master.MasterEntity

@Entity
@Table(
  name = "payment_methods",
  uniqueConstraints = [UniqueConstraint(name = "uq_payment_methods_name", columnNames = ["name"])],
)
class PaymentMethodEntity(name: String, displayOrder: Int = 0) : MasterEntity(name, displayOrder)
