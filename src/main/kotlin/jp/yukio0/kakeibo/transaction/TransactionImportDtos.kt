package jp.yukio0.kakeibo.transaction

/**
 * CSVインポートの結果。[commit] が false のときはプレビュー（未反映）で、[months] は「反映したら」の内訳を示す。 [errors]
 * が空でない場合は反映されず、行番号付きのエラーを返す。
 */
data class TransactionImportResult(
  val committed: Boolean,
  val totalRows: Int,
  val months: List<TransactionImportMonth>,
  val errors: List<TransactionImportError>,
)

data class TransactionImportMonth(
  val year: Int,
  val month: Int,
  /** 上書きで置き換えられる既存件数。 */
  val replacedCount: Int,
  /** CSVから取り込む件数。 */
  val importedCount: Int,
)

data class TransactionImportError(
  /** データ行の1始まりの行番号。ヘッダやファイル全体のエラーは 0。 */
  val row: Int,
  val message: String,
)
