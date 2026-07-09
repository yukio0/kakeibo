package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransactionRepositoryTests {

  @Autowired private lateinit var categoryRepository: CategoryRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  @Test
  fun monthlySearchUsesStartInclusiveAndNextMonthExclusiveAndStableOrder() {
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "取引検索Repositoryテスト",
          type = TransactionType.EXPENSE,
          displayOrder = 910,
        )
      )
    val feb28 =
      saveTransaction(
        category = category,
        transactionDate = LocalDate.of(2028, 2, 28),
        displayOrder = 2,
      )
    val feb29 =
      saveTransaction(
        category = category,
        transactionDate = LocalDate.of(2028, 2, 29),
        displayOrder = 1,
      )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2028, 1, 31),
      displayOrder = 0,
    )
    saveTransaction(
      category = category,
      transactionDate = LocalDate.of(2028, 3, 1),
      displayOrder = 0,
    )

    val transactions =
      transactionRepository
        .findAllByTransactionDateGreaterThanEqualAndTransactionDateLessThanOrderByDisplayOrderAscIdAsc(
          LocalDate.of(2028, 2, 1),
          LocalDate.of(2028, 3, 1),
        )

    assertEquals(listOf(feb29.id, feb28.id), transactions.map { it.id })
  }

  private fun saveTransaction(
    category: CategoryEntity,
    transactionDate: LocalDate,
    displayOrder: Int,
  ): TransactionEntity =
    transactionRepository.saveAndFlush(
      TransactionEntity(
        category = category,
        type = TransactionType.EXPENSE,
        transactionDate = transactionDate,
        amount = 1000,
        memo = transactionDate.toString(),
        displayOrder = displayOrder,
      )
    )
}
