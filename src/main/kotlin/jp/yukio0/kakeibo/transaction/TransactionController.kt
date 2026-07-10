package jp.yukio0.kakeibo.transaction

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/transactions")
class TransactionController(private val transactionService: TransactionService) {

  @GetMapping
  fun findMonthly(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
  ): List<TransactionResponse> = transactionService.findMonthly(year, month)

  @PutMapping("/monthly")
  fun saveMonthly(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
    @RequestBody requests: List<TransactionMonthlySaveRequest>,
  ): TransactionMonthlySaveResponse = transactionService.saveMonthly(year, month, requests)
}
