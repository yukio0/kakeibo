package jp.yukio0.kakeibo.transaction

import java.time.LocalDate
import jp.yukio0.kakeibo.category.CategoryRepository
import jp.yukio0.kakeibo.domain.TransactionType
import jp.yukio0.kakeibo.paymentmethod.PaymentMethodRepository
import jp.yukio0.kakeibo.transfer.TransferAccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * エクスポート形式のCSVを取り込む。CSVに含まれる月だけを、その内容で置き換える（月単位の上書き）。 名前でマスタを解決し、未解決や不正な行があれば全体を反映せず、行番号付きのエラーを返す。
 */
@Service
class TransactionImportService(
  private val transactionService: TransactionService,
  private val transactionRepository: TransactionRepository,
  private val categoryRepository: CategoryRepository,
  private val paymentMethodRepository: PaymentMethodRepository,
  private val transferAccountRepository: TransferAccountRepository,
) {

  private data class ResolvedRow(
    val year: Int,
    val month: Int,
    val request: TransactionMonthlySaveRequest,
  )

  @Transactional
  fun import(bytes: ByteArray, commit: Boolean): TransactionImportResult {
    val records = parseCsv(decode(bytes))
    if (records.isEmpty()) {
      return failure(0, TransactionImportError(0, "CSVが空です"))
    }

    val header = records.first().map { it.trim() }
    if (header != EXPECTED_HEADER) {
      return failure(
        0,
        TransactionImportError(0, "ヘッダ行が想定と異なります。エクスポート形式のCSVを取り込んでください"),
      )
    }

    val dataRecords = records.drop(1).filterNot { row -> row.all { it.isBlank() } }
    val categoriesByNameType = categoryRepository.findAll().associateBy { it.name to it.type }
    val paymentMethodsByName = paymentMethodRepository.findAll().associateBy { it.name }
    val transferAccountsByName = transferAccountRepository.findAll().associateBy { it.name }

    val errors = mutableListOf<TransactionImportError>()
    val resolvedRows = mutableListOf<ResolvedRow>()

    dataRecords.forEachIndexed { index, rawRow ->
      val rowNumber = index + 1
      val row = rawRow.map { denormalize(it.trim()) }
      if (row.size != EXPECTED_HEADER.size) {
        errors.add(TransactionImportError(rowNumber, "列数が正しくありません（6列必要です）"))
        return@forEachIndexed
      }

      val type = parseType(row[1])
      if (type == null) {
        errors.add(TransactionImportError(rowNumber, "種別は「支出」「収入」「振替」のいずれかです"))
        return@forEachIndexed
      }
      val date = parseDate(row[0])
      if (date == null) {
        errors.add(TransactionImportError(rowNumber, "日付の形式が不正です（YYYY-MM-DD）"))
        return@forEachIndexed
      }
      val amount = parseAmount(row[4])
      if (amount == null || amount < 1) {
        errors.add(TransactionImportError(rowNumber, "金額は1以上の整数で入力してください"))
        return@forEachIndexed
      }
      val memo = row[5].ifEmpty { null }
      if (memo != null && memo.length > 500) {
        errors.add(TransactionImportError(rowNumber, "メモは500文字以内で入力してください"))
        return@forEachIndexed
      }

      val sourceName = row[2]
      val destinationName = row[3]
      val categoryId: Long
      val paymentMethodId: Long?
      if (type == TransactionType.TRANSFER) {
        val source = transferAccountsByName[sourceName]
        val destination = transferAccountsByName[destinationName]
        if (source == null) {
          errors.add(TransactionImportError(rowNumber, "振替元「$sourceName」が見つかりません"))
          return@forEachIndexed
        }
        if (destination == null) {
          errors.add(TransactionImportError(rowNumber, "振替先「$destinationName」が見つかりません"))
          return@forEachIndexed
        }
        categoryId = source.requiredId()
        paymentMethodId = destination.requiredId()
      } else {
        val category = categoriesByNameType[sourceName to type]
        if (category == null) {
          errors.add(TransactionImportError(rowNumber, "「${row[1]}」のカテゴリ「$sourceName」が見つかりません"))
          return@forEachIndexed
        }
        categoryId = category.requiredId()
        // 収入は支払い方法を持たないため、支払い方法列は無視して null にする。
        if (type == TransactionType.INCOME) {
          paymentMethodId = null
        } else {
          val paymentMethod = paymentMethodsByName[destinationName]
          if (paymentMethod == null) {
            errors.add(TransactionImportError(rowNumber, "支払い方法「$destinationName」が見つかりません"))
            return@forEachIndexed
          }
          paymentMethodId = paymentMethod.requiredId()
        }
      }

      resolvedRows.add(
        ResolvedRow(
          year = date.year,
          month = date.monthValue,
          request =
            TransactionMonthlySaveRequest(
              id = null,
              date = date.toString(),
              type = type,
              categoryId = categoryId,
              paymentMethodId = paymentMethodId,
              amount = amount,
              memo = memo,
              displayOrder = 0,
            ),
        )
      )
    }

    if (errors.isNotEmpty()) {
      return TransactionImportResult(
        committed = false,
        totalRows = dataRecords.size,
        months = emptyList(),
        errors = errors,
      )
    }

    val rowsByMonth = resolvedRows.groupBy { it.year to it.month }
    val months =
      rowsByMonth
        .map { (yearMonth, rows) ->
          TransactionImportMonth(
            year = yearMonth.first,
            month = yearMonth.second,
            replacedCount = countExisting(yearMonth.first, yearMonth.second),
            importedCount = rows.size,
          )
        }
        .sortedWith(compareBy({ it.year }, { it.month }))

    if (!commit) {
      return TransactionImportResult(
        committed = false,
        totalRows = dataRecords.size,
        months = months,
        errors = emptyList(),
      )
    }

    rowsByMonth.forEach { (yearMonth, rows) ->
      val requests = rows.mapIndexed { index, row -> row.request.copy(displayOrder = index * 10) }
      transactionService.saveMonthly(yearMonth.first, yearMonth.second, requests)
    }

    return TransactionImportResult(
      committed = true,
      totalRows = dataRecords.size,
      months = months,
      errors = emptyList(),
    )
  }

  private fun countExisting(year: Int, month: Int): Int {
    val period = MonthlyPeriod.from(year, month)
    return transactionRepository
      .countByTransactionDateGreaterThanEqualAndTransactionDateLessThan(
        period.startDate,
        period.endDateExclusive,
      )
      .toInt()
  }

  private fun failure(totalRows: Int, error: TransactionImportError): TransactionImportResult =
    TransactionImportResult(
      committed = false,
      totalRows = totalRows,
      months = emptyList(),
      errors = listOf(error),
    )

  private fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).removePrefix("﻿")

  private fun parseType(value: String): TransactionType? =
    when (value) {
      "支出" -> TransactionType.EXPENSE
      "収入" -> TransactionType.INCOME
      "振替" -> TransactionType.TRANSFER
      else -> null
    }

  private fun parseDate(value: String): LocalDate? =
    runCatching { LocalDate.parse(value) }.getOrNull()

  private fun parseAmount(value: String): Int? = value.replace(",", "").toIntOrNull()

  /** エクスポート時のCSVインジェクション無害化（先頭に `'`）を元に戻す。 */
  private fun denormalize(value: String): String =
    if (value.length >= 2 && value[0] == '\'' && value[1] in FORMULA_TRIGGER_CHARS) {
      value.substring(1)
    } else {
      value
    }

  private fun parseCsv(content: String): List<List<String>> {
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

  private companion object {
    val EXPECTED_HEADER = listOf("日付", "種別", "カテゴリ・振替元", "支払い方法・振替先", "金額", "メモ")
    val FORMULA_TRIGGER_CHARS = setOf('=', '+', '-', '@', '\t', '\r')
  }
}
