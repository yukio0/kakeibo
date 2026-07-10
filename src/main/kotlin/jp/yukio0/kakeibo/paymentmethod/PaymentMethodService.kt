package jp.yukio0.kakeibo.paymentmethod

import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.BadRequestException
import jp.yukio0.kakeibo.api.ResourceNotFoundException
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentMethodService(
  private val paymentMethodRepository: PaymentMethodRepository,
  private val transactionRepository: TransactionRepository,
) {

  @Transactional(readOnly = true)
  fun findAll(): List<PaymentMethodResponse> =
    paymentMethodRepository.findAllByOrderByDisplayOrderAscIdAsc().map { it.toResponse() }

  @Transactional
  fun create(request: PaymentMethodRequest): PaymentMethodResponse {
    val command = request.toCommand()
    if (paymentMethodRepository.existsByName(command.name)) {
      throw validationException(
        message = "同じ支払い方法名はすでに存在します",
        field = "name",
      )
    }

    val paymentMethod =
      paymentMethodRepository.save(
        PaymentMethodEntity(
          name = command.name,
          displayOrder = command.displayOrder,
        )
      )
    return paymentMethod.toResponse()
  }

  @Transactional
  fun update(id: Long, request: PaymentMethodRequest): PaymentMethodResponse {
    val paymentMethod = findPaymentMethod(id)
    val command = request.toCommand()
    if (paymentMethodRepository.existsByNameAndIdNot(command.name, id)) {
      throw validationException(
        message = "同じ支払い方法名はすでに存在します",
        field = "name",
      )
    }

    paymentMethod.name = command.name
    paymentMethod.displayOrder = command.displayOrder
    return paymentMethod.toResponse()
  }

  @Transactional
  fun delete(id: Long) {
    val paymentMethod = findPaymentMethod(id)
    if (paymentMethodRepository.count() <= 1) {
      throw BadRequestException("支払い方法は最低1件必要です")
    }
    if (transactionRepository.existsByPaymentMethodId(id)) {
      throw BadRequestException("使用中の支払い方法は削除できません")
    }

    paymentMethodRepository.delete(paymentMethod)
  }

  private fun findPaymentMethod(id: Long): PaymentMethodEntity =
    paymentMethodRepository.findById(id).orElseThrow { ResourceNotFoundException("支払い方法が見つかりません") }

  private fun PaymentMethodRequest.toCommand(): PaymentMethodCommand {
    val errors = mutableListOf<ApiFieldErrorResponse>()
    val normalizedName = name?.trim()
    if (normalizedName.isNullOrEmpty()) {
      errors += ApiFieldErrorResponse(field = "name", message = "支払い方法名を入力してください")
    }
    if (displayOrder == null) {
      errors += ApiFieldErrorResponse(field = "displayOrder", message = "表示順を入力してください")
    } else if (displayOrder < 0) {
      errors += ApiFieldErrorResponse(field = "displayOrder", message = "表示順は0以上で入力してください")
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("入力内容に誤りがあります", errors)
    }
    return PaymentMethodCommand(
      name = normalizedName!!,
      displayOrder = displayOrder!!,
    )
  }

  private fun PaymentMethodEntity.toResponse(): PaymentMethodResponse =
    PaymentMethodResponse(
      id = id ?: error("Payment method id is not assigned"),
      name = name,
      displayOrder = displayOrder,
    )

  private data class PaymentMethodCommand(
    val name: String,
    val displayOrder: Int,
  )

  private fun validationException(message: String, field: String): ApiValidationException =
    ApiValidationException(
      message = message,
      errors = listOf(ApiFieldErrorResponse(field = field, message = message)),
    )
}
