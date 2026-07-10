package jp.yukio0.kakeibo.paymentmethod

import jp.yukio0.kakeibo.master.MasterCrudService
import jp.yukio0.kakeibo.master.MasterLabels
import jp.yukio0.kakeibo.master.normalizedName
import jp.yukio0.kakeibo.master.requiredDisplayOrder
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service

@Service
class PaymentMethodService(
  private val paymentMethodRepository: PaymentMethodRepository,
  private val transactionRepository: TransactionRepository,
) :
  MasterCrudService<PaymentMethodEntity, PaymentMethodRequest, PaymentMethodResponse>(
    paymentMethodRepository
  ) {

  override val labels =
    MasterLabels(
      notFound = "支払い方法が見つかりません",
      duplicateName = "同じ支払い方法名はすでに存在します",
      lastRemaining = "支払い方法は最低1件必要です",
      inUse = "使用中の支払い方法は削除できません",
    )

  override fun findAllSorted(): List<PaymentMethodEntity> =
    paymentMethodRepository.findAllByOrderByDisplayOrderAscIdAsc()

  override fun newEntity(request: PaymentMethodRequest): PaymentMethodEntity =
    PaymentMethodEntity(
      name = request.normalizedName,
      displayOrder = request.requiredDisplayOrder,
    )

  override fun applyTo(entity: PaymentMethodEntity, request: PaymentMethodRequest) {
    entity.name = request.normalizedName
    entity.displayOrder = request.requiredDisplayOrder
  }

  override fun toResponse(entity: PaymentMethodEntity): PaymentMethodResponse =
    PaymentMethodResponse(
      id = entity.requiredId(),
      name = entity.name,
      displayOrder = entity.displayOrder,
    )

  override fun existsSameName(request: PaymentMethodRequest, excludedId: Long?): Boolean =
    if (excludedId == null) {
      paymentMethodRepository.existsByName(request.normalizedName)
    } else {
      paymentMethodRepository.existsByNameAndIdNot(request.normalizedName, excludedId)
    }

  override fun isLastRemaining(entity: PaymentMethodEntity): Boolean =
    paymentMethodRepository.count() <= 1

  override fun isUsedByTransaction(id: Long): Boolean =
    transactionRepository.existsByPaymentMethodId(id)
}
