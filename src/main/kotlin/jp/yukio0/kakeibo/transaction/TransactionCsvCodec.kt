package jp.yukio0.kakeibo.transaction

import java.nio.charset.StandardCharsets
import jp.yukio0.kakeibo.domain.TransactionType

data class TransactionCsvRecord(
  val date: String,
  val type: TransactionType,
  val categoryOrTransferSource: String,
  val paymentMethodOrTransferDestination: String,
  val amount: String,
  val memo: String,
)

/** 家計簿CSVの形式、エスケープ、種別ラベルを一元管理する。 */
object TransactionCsvCodec {
  val HEADER = listOf("日付", "種別", "カテゴリ・振替元", "支払い方法・振替先", "金額", "メモ")

  fun encode(records: List<TransactionCsvRecord>): ByteArray {
    val csv = buildString {
      appendRow(this, HEADER)
      records.forEach { record ->
        appendRow(
          this,
          listOf(
            record.date,
            typeLabel(record.type),
            record.categoryOrTransferSource,
            record.paymentMethodOrTransferDestination,
            record.amount,
            record.memo,
          ),
        )
      }
    }
    return csv.toByteArray(StandardCharsets.UTF_8)
  }

  /** UTF-8 BOMの有無を吸収し、引用符・改行を解釈したレコードを返す。 */
  fun decode(bytes: ByteArray): List<List<String>> =
    parseRecords(String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF"))

  fun typeLabel(type: TransactionType): String =
    when (type) {
      TransactionType.EXPENSE -> "支出"
      TransactionType.INCOME -> "収入"
      TransactionType.TRANSFER -> "振替"
    }

  fun parseType(label: String): TransactionType? =
    when (label) {
      "支出" -> TransactionType.EXPENSE
      "収入" -> TransactionType.INCOME
      "振替" -> TransactionType.TRANSFER
      else -> null
    }

  /** エクスポート時に無害化したセルを、インポート用の値へ戻す。 */
  fun normalizeImportedCell(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.length >= 2 && trimmed[0] == '\'' && trimmed[1] in FORMULA_TRIGGER_CHARS) {
      trimmed.substring(1)
    } else {
      trimmed
    }
  }

  private fun appendRow(target: StringBuilder, values: List<String>) {
    target.append(values.joinToString(",") { value -> "\"" + escapeValue(value) + "\"" })
    target.append("\r\n")
  }

  /** 表計算ソフトが数式として扱う先頭文字を無害化し、引用符を二重化する。 */
  private fun escapeValue(value: String): String {
    val neutralized =
      if (value.isNotEmpty() && value.first() in FORMULA_TRIGGER_CHARS) "'$value" else value
    return neutralized.replace("\"", "\"\"")
  }

  private fun parseRecords(content: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var record = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0

    fun endField() {
      record.add(field.toString())
      field.setLength(0)
    }

    fun endRecord() {
      endField()
      records.add(record)
      record = mutableListOf()
    }

    while (index < content.length) {
      val character = content[index]
      if (inQuotes) {
        if (character == '"') {
          if (index + 1 < content.length && content[index + 1] == '"') {
            field.append('"')
            index += 2
          } else {
            inQuotes = false
            index += 1
          }
        } else {
          field.append(character)
          index += 1
        }
      } else {
        when (character) {
          '"' -> {
            inQuotes = true
            index += 1
          }
          ',' -> {
            endField()
            index += 1
          }
          '\r' -> index += 1
          '\n' -> {
            endRecord()
            index += 1
          }
          else -> {
            field.append(character)
            index += 1
          }
        }
      }
    }
    if (field.isNotEmpty() || record.isNotEmpty()) {
      endRecord()
    }
    return records
  }

  private val FORMULA_TRIGGER_CHARS = setOf('=', '+', '-', '@', '\t', '\r')
}
