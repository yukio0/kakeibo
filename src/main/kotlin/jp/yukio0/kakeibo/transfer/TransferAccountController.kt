package jp.yukio0.kakeibo.transfer

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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/transfer-accounts")
class TransferAccountController(private val transferAccountService: TransferAccountService) {

  @GetMapping fun findAll(): List<TransferAccountResponse> = transferAccountService.findAll()

  @PostMapping
  fun create(
    @Valid @RequestBody request: TransferAccountRequest
  ): ResponseEntity<TransferAccountResponse> =
    ResponseEntity.status(HttpStatus.CREATED).body(transferAccountService.create(request))

  @PutMapping("/{id}")
  fun update(
    @PathVariable id: Long,
    @Valid @RequestBody request: TransferAccountRequest,
  ): TransferAccountResponse = transferAccountService.update(id, request)

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(@PathVariable id: Long) {
    transferAccountService.delete(id)
  }
}
