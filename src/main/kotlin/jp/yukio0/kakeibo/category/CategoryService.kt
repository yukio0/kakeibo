package jp.yukio0.kakeibo.category

import jp.yukio0.kakeibo.api.BadRequestException
import jp.yukio0.kakeibo.api.ResourceNotFoundException
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CategoryService(
  private val categoryRepository: CategoryRepository,
  private val transactionRepository: TransactionRepository,
) {

  @Transactional(readOnly = true)
  fun findAll(): List<CategoryResponse> =
    categoryRepository.findAllByOrderByTypeAscDisplayOrderAscIdAsc().map { it.toResponse() }

  @Transactional
  fun create(request: CategoryRequest): CategoryResponse {
    val command = request.toCommand()
    if (categoryRepository.existsByNameAndType(command.name, command.type)) {
      throw BadRequestException("同じ種別のカテゴリ名はすでに存在します")
    }

    val category =
      categoryRepository.save(
        CategoryEntity(
          name = command.name,
          type = command.type,
          displayOrder = command.displayOrder,
        )
      )
    return category.toResponse()
  }

  @Transactional
  fun update(id: Long, request: CategoryRequest): CategoryResponse {
    val category = findCategory(id)
    val command = request.toCommand()
    if (categoryRepository.existsByNameAndTypeAndIdNot(command.name, command.type, id)) {
      throw BadRequestException("同じ種別のカテゴリ名はすでに存在します")
    }

    category.name = command.name
    category.type = command.type
    category.displayOrder = command.displayOrder
    return category.toResponse()
  }

  @Transactional
  fun delete(id: Long) {
    val category = findCategory(id)
    if (transactionRepository.existsByCategoryId(id)) {
      throw BadRequestException("使用中のカテゴリは削除できません")
    }

    categoryRepository.delete(category)
  }

  private fun findCategory(id: Long): CategoryEntity =
    categoryRepository.findById(id).orElseThrow { ResourceNotFoundException("カテゴリが見つかりません") }

  private fun CategoryRequest.toCommand(): CategoryCommand {
    val normalizedName = name?.trim()
    if (normalizedName.isNullOrEmpty() || type == null || displayOrder == null) {
      throw BadRequestException("入力内容に誤りがあります")
    }
    return CategoryCommand(
      name = normalizedName,
      type = type,
      displayOrder = displayOrder,
    )
  }

  private fun CategoryEntity.toResponse(): CategoryResponse =
    CategoryResponse(
      id = id ?: error("Category id is not assigned"),
      name = name,
      type = type,
      displayOrder = displayOrder,
    )

  private data class CategoryCommand(
    val name: String,
    val type: TransactionType,
    val displayOrder: Int,
  )
}
