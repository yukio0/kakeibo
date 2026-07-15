package jp.yukio0.kakeibo.budget

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.persistence.AuditableEntity

@Entity
@Table(
  name = "monthly_budgets",
  uniqueConstraints =
    [
      UniqueConstraint(
        name = "uq_monthly_budgets_year_month",
        columnNames = ["budget_year", "budget_month"],
      )
    ],
)
class MonthlyBudgetEntity(
  @field:Column(name = "budget_year", nullable = false) var budgetYear: Int,
  @field:Column(name = "budget_month", nullable = false) var budgetMonth: Int,
  @field:Column(name = "overall_budget") var overallBudget: Int? = null,
  @field:OneToMany(
    mappedBy = "monthlyBudget",
    cascade = [CascadeType.ALL],
    orphanRemoval = true,
  )
  val categoryBudgets: MutableList<CategoryBudgetEntity> = mutableListOf(),
) : AuditableEntity() {

  fun addCategoryBudget(category: CategoryEntity, amount: Int) {
    categoryBudgets.add(
      CategoryBudgetEntity(monthlyBudget = this, category = category, amount = amount)
    )
  }
}

@Entity
@Table(
  name = "category_budgets",
  uniqueConstraints =
    [
      UniqueConstraint(
        name = "uq_category_budgets_month_category",
        columnNames = ["monthly_budget_id", "category_id"],
      )
    ],
)
class CategoryBudgetEntity(
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "monthly_budget_id", nullable = false)
  var monthlyBudget: MonthlyBudgetEntity,
  @field:ManyToOne(fetch = FetchType.LAZY)
  @field:JoinColumn(name = "category_id", nullable = false)
  var category: CategoryEntity,
  @field:Column(nullable = false) var amount: Int,
) : AuditableEntity()
