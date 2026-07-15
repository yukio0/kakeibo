package jp.yukio0.kakeibo.recurring

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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
@RequestMapping("/api/recurring-templates")
class RecurringTransactionController(
  private val recurringTransactionService: RecurringTransactionService
) {

  @GetMapping
  fun findAll(): List<RecurringTransactionTemplateResponse> = recurringTransactionService.findAll()

  @PostMapping
  fun create(
    @Valid @RequestBody request: RecurringTransactionTemplateRequest
  ): ResponseEntity<RecurringTransactionTemplateResponse> =
    ResponseEntity.status(HttpStatus.CREATED).body(recurringTransactionService.create(request))

  @PutMapping("/{id}")
  fun update(
    @PathVariable id: Long,
    @Valid @RequestBody request: RecurringTransactionTemplateRequest,
  ): RecurringTransactionTemplateResponse = recurringTransactionService.update(id, request)

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(@PathVariable id: Long) {
    recurringTransactionService.delete(id)
  }

  @GetMapping("/candidates")
  fun findCandidates(
    @RequestParam(required = false) year: Int?,
    @RequestParam(required = false) month: Int?,
  ): RecurringTransactionCandidatesResponse =
    recurringTransactionService.findCandidates(year, month)

  @PostMapping("/register")
  fun register(
    @Valid @RequestBody request: RecurringTransactionRegisterRequest
  ): RecurringTransactionRegisterResponse = recurringTransactionService.register(request)
}
