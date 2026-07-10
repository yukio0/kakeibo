package jp.yukio0.kakeibo.category

import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.master.MasterCrudService
import jp.yukio0.kakeibo.master.MasterLabels
import jp.yukio0.kakeibo.master.normalizedName
import jp.yukio0.kakeibo.master.requiredDisplayOrder
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service

@Service
class CategoryService(
  private val categoryRepository: CategoryRepository,
  private val transactionRepository: TransactionRepository,
) : MasterCrudService<CategoryEntity, CategoryRequest, CategoryResponse>(categoryRepository) {

  override val labels =
    MasterLabels(
      notFound = "カテゴリが見つかりません",
      duplicateName = "同じ種別のカテゴリ名はすでに存在します",
      lastRemaining = "各種別のカテゴリは最低1件必要です",
      inUse = "使用中のカテゴリは削除できません",
    )

  override fun findAllSorted(): List<CategoryEntity> =
    categoryRepository.findAllByOrderByTypeAscDisplayOrderAscIdAsc()

  override fun newEntity(request: CategoryRequest): CategoryEntity =
    CategoryEntity(
      name = request.normalizedName,
      type = request.requiredType,
      displayOrder = request.requiredDisplayOrder,
    )

  override fun applyTo(entity: CategoryEntity, request: CategoryRequest) {
    entity.name = request.normalizedName
    entity.type = request.requiredType
    entity.displayOrder = request.requiredDisplayOrder
  }

  override fun toResponse(entity: CategoryEntity): CategoryResponse =
    CategoryResponse(
      id = entity.requiredId(),
      name = entity.name,
      type = entity.type,
      displayOrder = entity.displayOrder,
    )

  override fun existsSameName(request: CategoryRequest, excludedId: Long?): Boolean =
    if (excludedId == null) {
      categoryRepository.existsByNameAndType(request.normalizedName, request.requiredType)
    } else {
      categoryRepository.existsByNameAndTypeAndIdNot(
        request.normalizedName,
        request.requiredType,
        excludedId,
      )
    }

  override fun isLastRemaining(entity: CategoryEntity): Boolean =
    categoryRepository.countByType(entity.type) <= 1

  override fun isUsedByTransaction(id: Long): Boolean = transactionRepository.existsByCategoryId(id)

  override fun validate(request: CategoryRequest) {
    if (request.requiredType == TransactionType.TRANSFER) {
      val message = "カテゴリ種別は支出または収入を選択してください"
      throw ApiValidationException(
        message = "入力内容に誤りがあります",
        errors = listOf(ApiFieldErrorResponse(field = "type", message = message)),
      )
    }
  }

  private val CategoryRequest.requiredType: TransactionType
    get() = checkNotNull(type)
}
