package jp.yukio0.kakeibo.persistence

import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass

@MappedSuperclass
abstract class IdentifiableEntity {

  @field:Id
  @field:GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: Long? = null
    protected set

  /** 永続化済みのエンティティにだけ使う。未採番なら実装のバグなので例外を投げる。 */
  fun requiredId(): Long = id ?: error("${this::class.simpleName} id is not assigned")
}
