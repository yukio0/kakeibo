package jp.yukio0.kakeibo.category

import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
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
      throw validationException(
        message = "同じ種別のカテゴリ名はすでに存在します",
        field = "name",
      )
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
      throw validationException(
        message = "同じ種別のカテゴリ名はすでに存在します",
        field = "name",
      )
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
    val errors = mutableListOf<ApiFieldErrorResponse>()
    val normalizedName = name?.trim()
    if (normalizedName.isNullOrEmpty()) {
      errors += ApiFieldErrorResponse(field = "name", message = "カテゴリ名を入力してください")
    }
    if (type == null) {
      errors += ApiFieldErrorResponse(field = "type", message = "種別を選択してください")
    }
    if (displayOrder == null) {
      errors += ApiFieldErrorResponse(field = "displayOrder", message = "表示順を入力してください")
    } else if (displayOrder < 0) {
      errors += ApiFieldErrorResponse(field = "displayOrder", message = "表示順は0以上で入力してください")
    }
    if (errors.isNotEmpty()) {
      throw ApiValidationException("入力内容に誤りがあります", errors)
    }
    return CategoryCommand(
      name = normalizedName!!,
      type = type!!,
      displayOrder = displayOrder!!,
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

  private fun validationException(message: String, field: String): ApiValidationException =
    ApiValidationException(
      message = message,
      errors = listOf(ApiFieldErrorResponse(field = field, message = message)),
    )
}
