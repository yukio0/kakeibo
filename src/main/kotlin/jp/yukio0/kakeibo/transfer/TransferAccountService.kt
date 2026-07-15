package jp.yukio0.kakeibo.transfer

import jp.yukio0.kakeibo.master.MasterCrudService
import jp.yukio0.kakeibo.master.MasterLabels
import jp.yukio0.kakeibo.master.normalizedName
import jp.yukio0.kakeibo.master.requiredDisplayOrder
import jp.yukio0.kakeibo.recurring.RecurringTransactionTemplateRepository
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service

@Service
class TransferAccountService(
  private val transferAccountRepository: TransferAccountRepository,
  private val transactionRepository: TransactionRepository,
  private val recurringTemplateRepository: RecurringTransactionTemplateRepository,
) :
  MasterCrudService<TransferAccountEntity, TransferAccountRequest, TransferAccountResponse>(
    transferAccountRepository
  ) {

  override val labels =
    MasterLabels(
      notFound = "振替元・振替先が見つかりません",
      duplicateName = "同じ振替元・振替先名はすでに存在します",
      lastRemaining = "振替元・振替先は最低1件必要です",
      inUse = "使用中の振替元・振替先は削除できません",
    )

  override fun findAllSorted(): List<TransferAccountEntity> =
    transferAccountRepository.findAllByOrderByDisplayOrderAscIdAsc()

  override fun newEntity(request: TransferAccountRequest): TransferAccountEntity =
    TransferAccountEntity(
      name = request.normalizedName,
      displayOrder = request.requiredDisplayOrder,
    )

  override fun applyTo(entity: TransferAccountEntity, request: TransferAccountRequest) {
    entity.name = request.normalizedName
    entity.displayOrder = request.requiredDisplayOrder
  }

  override fun toResponse(entity: TransferAccountEntity): TransferAccountResponse =
    TransferAccountResponse(
      id = entity.requiredId(),
      name = entity.name,
      displayOrder = entity.displayOrder,
    )

  override fun existsSameName(request: TransferAccountRequest, excludedId: Long?): Boolean =
    if (excludedId == null) {
      transferAccountRepository.existsByName(request.normalizedName)
    } else {
      transferAccountRepository.existsByNameAndIdNot(request.normalizedName, excludedId)
    }

  override fun isLastRemaining(entity: TransferAccountEntity): Boolean =
    transferAccountRepository.count() <= 1

  override fun isUsed(id: Long): Boolean =
    transactionRepository.existsByTransferSourceIdOrTransferDestinationId(id, id) ||
      recurringTemplateRepository.existsByTransferSourceIdOrTransferDestinationId(id, id)
}
