package jp.yukio0.kakeibo.transfer

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jp.yukio0.kakeibo.persistence.AuditableEntity

@Entity
@Table(
  name = "transfer_accounts",
  uniqueConstraints =
    [UniqueConstraint(name = "uq_transfer_accounts_name", columnNames = ["name"])],
)
class TransferAccountEntity(
  @field:Column(nullable = false, length = 100) var name: String,
  @field:Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
) : AuditableEntity() {

  @field:Id
  @field:GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: Long? = null
    protected set
}
