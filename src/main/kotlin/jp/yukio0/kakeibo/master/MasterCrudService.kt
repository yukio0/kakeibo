package jp.yukio0.kakeibo.master

import jp.yukio0.kakeibo.api.ApiFieldErrorResponse
import jp.yukio0.kakeibo.api.ApiValidationException
import jp.yukio0.kakeibo.api.BadRequestException
import jp.yukio0.kakeibo.api.ResourceNotFoundException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

data class MasterLabels(
  val notFound: String,
  val duplicateName: String,
  val lastRemaining: String,
  val inUse: String,
)

/**
 * 名前と表示順を持つマスタデータの CRUD。
 *
 * 公開メソッドは Spring が CGLIB プロキシで上書きできるよう `open` にしておく必要がある。final のままだと `@Transactional`
 * が効かず、プロキシ上の未初期化フィールドを参照して NPE になる。
 */
abstract class MasterCrudService<E : MasterEntity, REQ : MasterRequest, RES : Any>(
  private val repository: JpaRepository<E, Long>
) {

  protected abstract val labels: MasterLabels

  protected abstract fun findAllSorted(): List<E>

  protected abstract fun newEntity(request: REQ): E

  protected abstract fun applyTo(entity: E, request: REQ)

  protected abstract fun toResponse(entity: E): RES

  protected abstract fun existsSameName(request: REQ, excludedId: Long?): Boolean

  protected abstract fun isLastRemaining(entity: E): Boolean

  protected abstract fun isUsedByTransaction(id: Long): Boolean

  /** 名前と表示順以外に固有の検査があるマスタだけが上書きする。 */
  protected open fun validate(request: REQ) {}

  @Transactional(readOnly = true)
  open fun findAll(): List<RES> = findAllSorted().map { toResponse(it) }

  @Transactional
  open fun create(request: REQ): RES {
    validate(request)
    rejectDuplicateName(request, excludedId = null)

    return toResponse(repository.save(newEntity(request)))
  }

  @Transactional
  open fun update(id: Long, request: REQ): RES {
    val entity = findOrThrow(id)
    validate(request)
    rejectDuplicateName(request, excludedId = id)

    applyTo(entity, request)
    return toResponse(entity)
  }

  @Transactional
  open fun delete(id: Long) {
    val entity = findOrThrow(id)
    if (isLastRemaining(entity)) {
      throw BadRequestException(labels.lastRemaining)
    }
    if (isUsedByTransaction(id)) {
      throw BadRequestException(labels.inUse)
    }

    repository.delete(entity)
  }

  private fun findOrThrow(id: Long): E =
    repository.findById(id).orElseThrow { ResourceNotFoundException(labels.notFound) }

  private fun rejectDuplicateName(request: REQ, excludedId: Long?) {
    if (existsSameName(request, excludedId)) {
      throw ApiValidationException(
        message = labels.duplicateName,
        errors = listOf(ApiFieldErrorResponse(field = "name", message = labels.duplicateName)),
      )
    }
  }
}
