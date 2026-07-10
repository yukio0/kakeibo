package jp.yukio0.kakeibo.paymentmethod

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
@RequestMapping("/api/payment-methods")
class PaymentMethodController(private val paymentMethodService: PaymentMethodService) {

  @GetMapping fun findAll(): List<PaymentMethodResponse> = paymentMethodService.findAll()

  @PostMapping
  fun create(
    @Valid @RequestBody request: PaymentMethodRequest
  ): ResponseEntity<PaymentMethodResponse> =
    ResponseEntity.status(HttpStatus.CREATED).body(paymentMethodService.create(request))

  @PutMapping("/{id}")
  fun update(
    @PathVariable id: Long,
    @Valid @RequestBody request: PaymentMethodRequest,
  ): PaymentMethodResponse = paymentMethodService.update(id, request)

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(@PathVariable id: Long) {
    paymentMethodService.delete(id)
  }
}
