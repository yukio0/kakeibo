package jp.yukio0.kakeibo.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionTargetRulesTests {

  @Test
  fun acceptsTheValidTargetCombinationForEveryTransactionType() {
    assertTrue(
      TransactionTargetRules.validate(
          TransactionType.EXPENSE,
          TransactionTargetIds(categoryId = 1, paymentMethodId = 2),
        )
        .isEmpty()
    )
    assertTrue(
      TransactionTargetRules.validate(
          TransactionType.INCOME,
          TransactionTargetIds(categoryId = 1),
        )
        .isEmpty()
    )
    assertTrue(
      TransactionTargetRules.validate(
          TransactionType.TRANSFER,
          TransactionTargetIds(transferSourceId = 1, transferDestinationId = 2),
        )
        .isEmpty()
    )
  }

  @Test
  fun returnsFieldSpecificViolationsForAnInvalidCombination() {
    val violations =
      TransactionTargetRules.validate(
        TransactionType.INCOME,
        TransactionTargetIds(
          paymentMethodId = 2,
          transferSourceId = 3,
          transferDestinationId = 4,
        ),
      )

    assertEquals(
      listOf(
        TransactionTargetViolation(TransactionTargetField.CATEGORY, "カテゴリを選択してください"),
        TransactionTargetViolation(
          TransactionTargetField.PAYMENT_METHOD,
          "収入では支払い方法を指定できません",
        ),
        TransactionTargetViolation(
          TransactionTargetField.TRANSFER_SOURCE,
          "収入では振替元を指定できません",
        ),
        TransactionTargetViolation(
          TransactionTargetField.TRANSFER_DESTINATION,
          "収入では振替先を指定できません",
        ),
      ),
      violations,
    )
  }

  @Test
  fun categoryTypeMustMatchIncomeOrExpense() {
    assertTrue(
      TransactionTargetRules.categoryMatches(TransactionType.EXPENSE, TransactionType.EXPENSE)
    )
    assertFalse(
      TransactionTargetRules.categoryMatches(TransactionType.EXPENSE, TransactionType.INCOME)
    )
    assertFalse(
      TransactionTargetRules.categoryMatches(TransactionType.TRANSFER, TransactionType.TRANSFER)
    )
  }
}
