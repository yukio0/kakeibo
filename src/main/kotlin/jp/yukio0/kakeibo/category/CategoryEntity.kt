package jp.yukio0.kakeibo.category

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.master.MasterEntity

@Entity
@Table(
  name = "categories",
  uniqueConstraints =
    [UniqueConstraint(name = "uq_categories_name_type", columnNames = ["name", "type"])],
)
class CategoryEntity(
  name: String,
  @field:Enumerated(EnumType.STRING)
  @field:Column(nullable = false, length = 20)
  var type: TransactionType,
  displayOrder: Int = 0,
) : MasterEntity(name, displayOrder)
