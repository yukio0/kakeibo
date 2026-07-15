package jp.yukio0.kakeibo.budget

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/budgets/monthly")
class MonthlyBudgetController(private val monthlyBudgetService: MonthlyBudgetService) {

  @GetMapping
  fun getMonthlyBudget(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
  ): MonthlyBudgetResponse = monthlyBudgetService.getMonthlyBudget(year, month)

  @PutMapping
  fun updateMonthlyBudget(
    @Valid @RequestBody request: MonthlyBudgetUpdateRequest
  ): MonthlyBudgetResponse = monthlyBudgetService.updateMonthlyBudget(request)
}
