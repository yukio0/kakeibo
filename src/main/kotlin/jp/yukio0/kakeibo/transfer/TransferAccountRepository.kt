package jp.yukio0.kakeibo.transfer

import org.springframework.data.jpa.repository.JpaRepository

interface TransferAccountRepository : JpaRepository<TransferAccountEntity, Long> {
  fun findAllByOrderByDisplayOrderAscIdAsc(): List<TransferAccountEntity>

  fun findByName(name: String): TransferAccountEntity?

  fun existsByName(name: String): Boolean

  fun existsByNameAndIdNot(name: String, id: Long): Boolean
}
