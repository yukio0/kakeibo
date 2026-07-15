package jp.yukio0.kakeibo.budget

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface MonthlyBudgetRepository : JpaRepository<MonthlyBudgetEntity, Long> {

  @EntityGraph(attributePaths = ["categoryBudgets", "categoryBudgets.category"])
  fun findByBudgetYearAndBudgetMonth(budgetYear: Int, budgetMonth: Int): MonthlyBudgetEntity?
}
