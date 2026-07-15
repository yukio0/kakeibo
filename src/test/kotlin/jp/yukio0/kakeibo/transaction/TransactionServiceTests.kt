package jp.yukio0.kakeibo.transaction

import jakarta.validation.Validator
import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodEntity
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import jp.yukio0.kakeibo.transfer.TransferAccountEntity
import jp.yukio0.kakeibo.transfer.TransferAccountRepository
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TransactionServiceTests {

  private val transactionRepository = mock(TransactionRepository::class.java)
  private val categoryRepository = mock(CategoryRepository::class.java)
  private val paymentMethodRepository = mock(PaymentMethodRepository::class.java)
  private val transferAccountRepository = mock(TransferAccountRepository::class.java)
  private val validator = mock(Validator::class.java)
  private val service =
    TransactionService(
      transactionRepository,
      categoryRepository,
      paymentMethodRepository,
      transferAccountRepository,
      validator,
    )

  @Test
  fun monthlySaveDoesNotDeleteRowAddedAfterInitialSnapshot() {
    val initialRow = transaction(id = 1, memo = "initial")
    val concurrentlyAddedRow = transaction(id = 2, memo = "concurrent")
    var masterResolutionStarted = false

    `when`(
        transactionRepository
          .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
            LocalDate.of(2099, 1, 1),
            LocalDate.of(2099, 2, 1),
          )
      )
      .thenAnswer {
        if (masterResolutionStarted) {
          listOf(initialRow, concurrentlyAddedRow)
        } else {
          listOf(initialRow)
        }
      }
    `when`(categoryRepository.findAllById(emptySet<Long>())).thenAnswer {
      masterResolutionStarted = true
      emptyList<CategoryEntity>()
    }
    `when`(paymentMethodRepository.findAllById(emptySet<Long>()))
      .thenReturn(emptyList<PaymentMethodEntity>())
    `when`(transferAccountRepository.findAllById(emptySet<Long>()))
      .thenReturn(emptyList<TransferAccountEntity>())

    val result = service.saveMonthly(year = 2099, month = 1, requests = emptyList())

    assertTrue(result.isEmpty())
    verify(transactionRepository).deleteAll(listOf(initialRow))
    verify(transactionRepository, times(1))
      .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
        LocalDate.of(2099, 1, 1),
        LocalDate.of(2099, 2, 1),
      )
  }

  private fun transaction(id: Long, memo: String): TransactionEntity =
    TransactionEntity(
        type = TransactionType.EXPENSE,
        transactionDate = LocalDate.of(2099, 1, 1),
        amount = 100,
        memo = memo,
      )
      .also { entity ->
        val idField = entity.javaClass.superclass.superclass.getDeclaredField("id")
        idField.isAccessible = true
        idField.set(entity, id)
      }
}
