package jp.yukio0.kakeibo.domain

/** 取引種別ごとに選択できるカテゴリ・支払い方法・振替口座の組み合わせ。 */
data class TransactionTargetIds(
  val categoryId: Long? = null,
  val paymentMethodId: Long? = null,
  val transferSourceId: Long? = null,
  val transferDestinationId: Long? = null,
)

enum class TransactionTargetField(val fieldName: String) {
  CATEGORY("categoryId"),
  PAYMENT_METHOD("paymentMethodId"),
  TRANSFER_SOURCE("transferSourceId"),
  TRANSFER_DESTINATION("transferDestinationId"),
}

data class TransactionTargetViolation(
  val field: TransactionTargetField,
  val message: String,
)

/** 通常取引と定期取引で共有する、種別と入力対象の組み合わせ規則。 */
object TransactionTargetRules {
  const val CATEGORY_TYPE_MISMATCH_MESSAGE = "種別に合うカテゴリを選択してください"

  fun validate(
    type: TransactionType,
    targets: TransactionTargetIds,
  ): List<TransactionTargetViolation> =
    when (type) {
      TransactionType.EXPENSE ->
        listOfNotNull(
          required(TransactionTargetField.CATEGORY, targets.categoryId, "カテゴリを選択してください"),
          required(
            TransactionTargetField.PAYMENT_METHOD,
            targets.paymentMethodId,
            "支払い方法を選択してください",
          ),
          forbidden(
            TransactionTargetField.TRANSFER_SOURCE,
            targets.transferSourceId,
            "支出では振替元を指定できません",
          ),
          forbidden(
            TransactionTargetField.TRANSFER_DESTINATION,
            targets.transferDestinationId,
            "支出では振替先を指定できません",
          ),
        )
      TransactionType.INCOME ->
        listOfNotNull(
          required(TransactionTargetField.CATEGORY, targets.categoryId, "カテゴリを選択してください"),
          forbidden(
            TransactionTargetField.PAYMENT_METHOD,
            targets.paymentMethodId,
            "収入では支払い方法を指定できません",
          ),
          forbidden(
            TransactionTargetField.TRANSFER_SOURCE,
            targets.transferSourceId,
            "収入では振替元を指定できません",
          ),
          forbidden(
            TransactionTargetField.TRANSFER_DESTINATION,
            targets.transferDestinationId,
            "収入では振替先を指定できません",
          ),
        )
      TransactionType.TRANSFER ->
        listOfNotNull(
          forbidden(
            TransactionTargetField.CATEGORY,
            targets.categoryId,
            "振替ではカテゴリを指定できません",
          ),
          forbidden(
            TransactionTargetField.PAYMENT_METHOD,
            targets.paymentMethodId,
            "振替では支払い方法を指定できません",
          ),
          required(
            TransactionTargetField.TRANSFER_SOURCE,
            targets.transferSourceId,
            "振替元を選択してください",
          ),
          required(
            TransactionTargetField.TRANSFER_DESTINATION,
            targets.transferDestinationId,
            "振替先を選択してください",
          ),
        )
    }

  fun categoryMatches(type: TransactionType, categoryType: TransactionType): Boolean =
    type != TransactionType.TRANSFER && type == categoryType

  private fun required(
    field: TransactionTargetField,
    value: Long?,
    message: String,
  ): TransactionTargetViolation? =
    if (value == null) TransactionTargetViolation(field, message) else null

  private fun forbidden(
    field: TransactionTargetField,
    value: Long?,
    message: String,
  ): TransactionTargetViolation? =
    if (value != null) TransactionTargetViolation(field, message) else null
}
