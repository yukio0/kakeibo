package jp.yukio0.kakeibo.transfer

import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.BadRequestException
import jp.yukio0.kakeibo.api.ResourceNotFoundException
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransferAccountService(
  private val transferAccountRepository: TransferAccountRepository,
  private val transactionRepository: TransactionRepository,
) {

  @Transactional(readOnly = true)
  fun findAll(): List<TransferAccountResponse> =
    transferAccountRepository.findAllByOrderByDisplayOrderAscIdAsc().map { it.toResponse() }

  @Transactional
  fun create(request: TransferAccountRequest): TransferAccountResponse {
    val command = request.toCommand()
    if (transferAccountRepository.existsByName(command.name)) {
      throw validationException(
        message = "同じ振替元・振替先名はすでに存在します",
        field = "name",
      )
    }

    val transferAccount =
      transferAccountRepository.save(
        TransferAccountEntity(
          name = command.name,
          displayOrder = command.displayOrder,
        )
      )
    return transferAccount.toResponse()
  }

  @Transactional
  fun update(id: Long, request: TransferAccountRequest): TransferAccountResponse {
    val transferAccount = findTransferAccount(id)
    val command = request.toCommand()
    if (transferAccountRepository.existsByNameAndIdNot(command.name, id)) {
      throw validationException(
        message = "同じ振替元・振替先名はすでに存在します",
        field = "name",
      )
    }

    transferAccount.name = command.name
    transferAccount.displayOrder = command.displayOrder
    return transferAccount.toResponse()
  }

  @Transactional
  fun delete(id: Long) {
    val transferAccount = findTransferAccount(id)
    if (transferAccountRepository.count() <= 1) {
      throw BadRequestException("振替元・振替先は最低1件必要です")
    }
    if (transactionRepository.existsByTransferSourceIdOrTransferDestinationId(id, id)) {
      throw BadRequestException("使用中の振替元・振替先は削除できません")
    }

    transferAccountRepository.delete(transferAccount)
  }

  private fun findTransferAccount(id: Long): TransferAccountEntity =
    transferAccountRepository.findById(id).orElseThrow {
      ResourceNotFoundException("振替元・振替先が見つかりません")
    }

  private fun TransferAccountRequest.toCommand(): TransferAccountCommand {
    val errors = mutableListOf<ApiFieldErrorResponse>()
    val normalizedName = name?.trim()
    if (normalizedName.isNullOrEmpty()) {
      errors += ApiFieldErrorResponse(field = "name", message = "振替元・振替先名を入力してください")
    }
    if (displayOrder == null) {
      errors += ApiFieldErrorResponse(field = "displayOrder", message = "表示順を入力してください")
    } else if (displayOrder < 0) {
      errors += ApiFieldErrorResponse(field = "displayOrder", message = "表示順は0以上で入力してください")
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("入力内容に誤りがあります", errors)
    }
    return TransferAccountCommand(
      name = normalizedName!!,
      displayOrder = displayOrder!!,
    )
  }

  private fun TransferAccountEntity.toResponse(): TransferAccountResponse =
    TransferAccountResponse(
      id = id ?: error("Transfer account id is not assigned"),
      name = name,
      displayOrder = displayOrder,
    )

  private data class TransferAccountCommand(
    val name: String,
    val displayOrder: Int,
  )

  private fun validationException(message: String, field: String): ApiValidationException =
    ApiValidationException(
      message = message,
      errors = listOf(ApiFieldErrorResponse(field = field, message = message)),
    )
}
