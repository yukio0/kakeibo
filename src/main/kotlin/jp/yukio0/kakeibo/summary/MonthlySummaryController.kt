package jp.yukio0.kakeibo.summary

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/summary")
class MonthlySummaryController(private val monthlySummaryService: MonthlySummaryService) {

  @GetMapping("/monthly")
  fun getMonthlySummary(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
  ): MonthlySummaryResponse = monthlySummaryService.getMonthlySummary(year, month)

  @GetMapping("/monthly/categories")
  fun getMonthlyCategoryExpenses(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
  ): CategoryExpenseSummaryResponse = monthlySummaryService.getMonthlyCategoryExpenses(year, month)

  @GetMapping("/trend")
  fun getMonthlyTrend(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
    @RequestParam(required = false) months: Int?,
  ): MonthlyTrendResponse = monthlySummaryService.getMonthlyTrend(year, month, months)
}
