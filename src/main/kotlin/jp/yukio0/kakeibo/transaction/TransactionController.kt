package jp.yukio0.kakeibo.transaction

import jakarta.validation.Valid
import java.time.LocalDate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/transactions")
class TransactionController(private val transactionService: TransactionService) {

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  fun create(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
    @Valid @RequestBody request: TransactionSaveRequest,
  ): TransactionResponse = transactionService.create(year, month, request)

  @GetMapping
  fun findMonthly(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
  ): List<TransactionResponse> = transactionService.findMonthly(year, month)

  @GetMapping("/export")
  fun export(
    @RequestParam(required = false) startDate: LocalDate?,
    @RequestParam(required = false) endDate: LocalDate?,
  ): ResponseEntity<ByteArray> {
    val exportPeriod = TransactionExportPeriod.from(startDate, endDate)
    val csv =
      transactionService.exportCsv(exportPeriod) ?: return ResponseEntity.noContent().build()

    return ResponseEntity.ok()
      .contentType(MediaType("text", "csv", Charsets.UTF_8))
      .header(
        HttpHeaders.CONTENT_DISPOSITION,
        "attachment; filename=\"${exportPeriod.fileName()}\"",
      )
      .body(csv)
  }

  @PutMapping("/{id}")
  fun update(
    @PathVariable id: Long,
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
    @Valid @RequestBody request: TransactionSaveRequest,
  ): TransactionResponse = transactionService.update(year, month, id, request)

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(
    @PathVariable id: Long,
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
  ) {
    transactionService.delete(year, month, id)
  }

  @PutMapping("/monthly")
  fun saveMonthly(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
    @RequestBody requests: List<TransactionMonthlySaveRequest>,
  ): List<TransactionResponse> = transactionService.saveMonthly(year, month, requests)
}
