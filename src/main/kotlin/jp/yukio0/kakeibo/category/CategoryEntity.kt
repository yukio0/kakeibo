package jp.yukio0.kakeibo.category

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.persistence.AuditableEntity

@Entity
@Table(
  name = "categories",
  uniqueConstraints =
    [UniqueConstraint(name = "uq_categories_name_type", columnNames = ["name", "type"])],
)
class CategoryEntity(
  @field:Column(nullable = false, length = 100) var name: String,
  @field:Enumerated(EnumType.STRING)
  @field:Column(nullable = false, length = 20)
  var type: TransactionType,
  @field:Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
) : AuditableEntity() {

  @field:Id
  @field:GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: Long? = null
    protected set
}
