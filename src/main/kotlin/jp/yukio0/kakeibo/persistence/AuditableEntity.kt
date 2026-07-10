package jp.yukio0.kakeibo.persistence

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp

@MappedSuperclass
abstract class AuditableEntity : IdentifiableEntity() {

  @field:CreationTimestamp
  @field:Column(name = "created_at", nullable = false, updatable = false)
  var createdAt: Instant? = null
    protected set

  @field:UpdateTimestamp
  @field:Column(name = "updated_at", nullable = false)
  var updatedAt: Instant? = null
    protected set
}
