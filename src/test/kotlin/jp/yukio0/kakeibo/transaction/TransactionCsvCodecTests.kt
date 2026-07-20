package jp.yukio0.kakeibo.transaction

import jp.yukio0.kakeibo.domain.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TransactionCsvCodecTests {

  @Test
  fun headerAndTypeLabelsKeepTheImportExportContract() {
    assertEquals(
      listOf("日付", "種別", "カテゴリ・振替元", "支払い方法・振替先", "金額", "メモ"),
      TransactionCsvCodec.HEADER,
    )

    val labels =
      mapOf(
        TransactionType.EXPENSE to "支出",
        TransactionType.INCOME to "収入",
        TransactionType.TRANSFER to "振替",
      )
    labels.forEach { (type, label) ->
      assertEquals(label, TransactionCsvCodec.typeLabel(type))
      assertEquals(type, TransactionCsvCodec.parseType(label))
    }
    assertEquals(null, TransactionCsvCodec.parseType("その他"))
  }

  @Test
  fun encodedRecordsCanBeDecodedForImportWithoutBomOrFormulaPrefixes() {
    val records =
      listOf(
        TransactionCsvRecord(
          date = "2026-07-31",
          type = TransactionType.EXPENSE,
          categoryOrTransferSource = "=HYPERLINK(\"http://example.invalid\")",
          paymentMethodOrTransferDestination = "現金",
          amount = "1234",
          memo = "@SUM(A1), \"引用\"\n改行",
        ),
        TransactionCsvRecord(
          date = "2026-08-01",
          type = TransactionType.TRANSFER,
          categoryOrTransferSource = "財布",
          paymentMethodOrTransferDestination = "銀行口座",
          amount = "5000",
          memo = "振替",
        ),
      )

    val encoded = TransactionCsvCodec.encode(records)
    assertFalse(
      encoded
        .take(3)
        .toByteArray()
        .contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    )

    val decoded = TransactionCsvCodec.decode(encoded)
    assertEquals(TransactionCsvCodec.HEADER, decoded.first())
    assertEquals(
      listOf(
        "2026-07-31",
        "支出",
        "=HYPERLINK(\"http://example.invalid\")",
        "現金",
        "1234",
        "@SUM(A1), \"引用\"\n改行",
      ),
      decoded[1].map(TransactionCsvCodec::normalizeImportedCell),
    )
    assertEquals(
      listOf("2026-08-01", "振替", "財布", "銀行口座", "5000", "振替"),
      decoded[2].map(TransactionCsvCodec::normalizeImportedCell),
    )
  }

  @Test
  fun decoderAcceptsUtf8BomForImportedFiles() {
    val csv = "\uFEFF\"日付\",\"種別\",\"カテゴリ・振替元\",\"支払い方法・振替先\",\"金額\",\"メモ\"\r\n"

    assertEquals(
      listOf(TransactionCsvCodec.HEADER),
      TransactionCsvCodec.decode(csv.toByteArray(Charsets.UTF_8)),
    )
  }
}
