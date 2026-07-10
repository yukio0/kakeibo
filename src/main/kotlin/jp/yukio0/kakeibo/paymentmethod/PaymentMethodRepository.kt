package jp.yukio0.kakeibo.paymentmethod

import org.springframework.data.jpa.repository.JpaRepository

interface PaymentMethodRepository : JpaRepository<PaymentMethodEntity, Long> {
  fun findAllByOrderByDisplayOrderAscIdAsc(): List<PaymentMethodEntity>

  fun findByName(name: String): PaymentMethodEntity?

  fun existsByName(name: String): Boolean

  fun existsByNameAndIdNot(name: String, id: Long): Boolean
}
