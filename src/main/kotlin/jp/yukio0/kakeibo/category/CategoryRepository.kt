package jp.yukio0.kakeibo.category

import jp.yukio0.kakeibo.domain.TransactionType
import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<CategoryEntity, Long> {
  fun findAllByOrderByTypeAscDisplayOrderAscIdAsc(): List<CategoryEntity>

  fun findByNameAndType(name: String, type: TransactionType): CategoryEntity?

  fun countByType(type: TransactionType): Long

  fun existsByNameAndType(name: String, type: TransactionType): Boolean

  fun existsByNameAndTypeAndIdNot(name: String, type: TransactionType, id: Long): Boolean
}
