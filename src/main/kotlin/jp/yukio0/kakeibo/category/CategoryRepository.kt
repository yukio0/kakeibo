package jp.yukio0.kakeibo.category

import jp.yukio0.kakeibo.domain.TransactionType
import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<CategoryEntity, Long> {
  fun findAllByOrderByTypeAscDisplayOrderAscIdAsc(): List<CategoryEntity>

  fun existsByNameAndType(name: String, type: TransactionType): Boolean
}
