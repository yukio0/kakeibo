package jp.yukio0.kakeibo.persistence

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import jp.yukio0.kakeibo.category.CategoryEntity
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import jp.yukio0.kakeibo.transaction.TransactionEntity
import jp.yukio0.kakeibo.transaction.TransactionRepository
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceEntity
import jp.yukio0.kakeibo.trusteddevice.TrustedDeviceRepository
import jp.yukio0.kakeibo.user.AppUserEntity
import jp.yukio0.kakeibo.user.AppUserRepository
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersistenceRepositoryTests {

  @Autowired private lateinit var appUserRepository: AppUserRepository

  @Autowired private lateinit var categoryRepository: CategoryRepository

  @Autowired private lateinit var paymentMethodRepository: PaymentMethodRepository

  @Autowired private lateinit var transactionRepository: TransactionRepository

  @Autowired private lateinit var trustedDeviceRepository: TrustedDeviceRepository

  @Test
  fun repositoriesSaveAndLoadEntities() {
    val user =
      appUserRepository.saveAndFlush(
        AppUserEntity(
          username = "repository-test-user",
          passwordHash = "bcrypt-hash-for-test",
        )
      )
    val category =
      categoryRepository.saveAndFlush(
        CategoryEntity(
          name = "リポジトリテスト",
          type = TransactionType.EXPENSE,
          displayOrder = 999,
        )
      )
    val transaction =
      transactionRepository.saveAndFlush(
        TransactionEntity(
          category = category,
          paymentMethod =
            paymentMethodRepository.findByName("現金") ?: error("Payment method is not found"),
          type = TransactionType.EXPENSE,
          transactionDate = LocalDate.of(2026, 7, 8),
          amount = 1200,
          memo = "テスト",
          displayOrder = 1,
        )
      )
    val trustedDevice =
      trustedDeviceRepository.saveAndFlush(
        TrustedDeviceEntity(
          tokenHash = "a".repeat(64),
          deviceName = "Repository Test",
          expiresAt = Instant.now().plus(30, ChronoUnit.DAYS),
        )
      )

    assertNotNull(user.id)
    assertNotNull(user.createdAt)
    assertNotNull(user.updatedAt)
    assertEquals(user.id, appUserRepository.findByUsername(user.username)?.id)

    assertNotNull(category.id)
    assertNotNull(category.createdAt)
    assertEquals(
      category.id,
      categoryRepository.findById(category.id!!).orElseThrow().id,
    )

    assertNotNull(transaction.id)
    assertNotNull(transaction.updatedAt)
    assertEquals(transaction.id, transactionRepository.findById(transaction.id!!).orElseThrow().id)

    assertNotNull(trustedDevice.id)
    assertNotNull(trustedDevice.createdAt)
    assertEquals(
      trustedDevice.id,
      trustedDeviceRepository.findByTokenHash(trustedDevice.tokenHash)?.id,
    )
  }
}
