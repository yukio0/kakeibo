package jp.yukio0.kakeibo.transfer

import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jp.yukio0.kakeibo.master.MasterEntity

@Entity
@Table(
  name = "transfer_accounts",
  uniqueConstraints =
    [UniqueConstraint(name = "uq_transfer_accounts_name", columnNames = ["name"])],
)
class TransferAccountEntity(name: String, displayOrder: Int = 0) : MasterEntity(name, displayOrder)
