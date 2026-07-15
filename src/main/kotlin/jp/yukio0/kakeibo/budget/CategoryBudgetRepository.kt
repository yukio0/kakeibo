package jp.yukio0.kakeibo.budget

import org.springframework.data.jpa.repository.JpaRepository

interface CategoryBudgetRepository : JpaRepository<CategoryBudgetEntity, Long> {

  fun existsByCategoryId(categoryId: Long): Boolean
}
