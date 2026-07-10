package jp.yukio0.kakeibo.master

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jp.yukio0.kakeibo.persistence.AuditableEntity

@MappedSuperclass
abstract class MasterEntity(
  @field:Column(nullable = false, length = 100) var name: String,
  @field:Column(name = "display_order", nullable = false) var displayOrder: Int = 0,
) : AuditableEntity()
