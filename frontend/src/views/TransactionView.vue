<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  exportMonthlyTransactions,
  getCategories,
  getMonthlySummary,
  getPaymentMethods,
  getTransferAccounts,
  getTransactions,
  saveMonthlyTransactions,
  type Category,
  type MonthlySummary,
  type PaymentMethod,
  type Transaction,
  type TransactionMonthlySaveRequest,
  type TransactionType,
  type TransferAccount,
} from '@/api/kakeibo'

type TransactionRow = {
  localKey: string
  id: number | null
  date: string
  type: TransactionType
  categoryId: number | ''
  paymentMethodId: number | ''
  amount: number | ''
  memo: string
  deleted: boolean
}

type TransactionField =
  | 'id'
  | 'date'
  | 'type'
  | 'categoryId'
  | 'paymentMethodId'
  | 'amount'
  | 'memo'
  | 'displayOrder'

type TransactionFieldErrors = Partial<Record<TransactionField, string>>
type EditableField = 'date' | 'type' | 'categoryId' | 'paymentMethodId' | 'amount' | 'memo'
type ActiveCell = {
  rowKey: string
  field: EditableField
}
type FocusRestoreTarget = {
  field: EditableField
  entryIndex?: number
  blankRow?: boolean
}
type RowEditSnapshot = Pick<
  TransactionRow,
  'date' | 'type' | 'categoryId' | 'paymentMethodId' | 'amount' | 'memo' | 'deleted'
>

type SaveEntry = {
  row: TransactionRow
  request: TransactionMonthlySaveRequest
}

const today = new Date()
const period = reactive({
  year: today.getFullYear(),
  month: today.getMonth() + 1,
})

const categories = ref<Category[]>([])
const paymentMethods = ref<PaymentMethod[]>([])
const transferAccounts = ref<TransferAccount[]>([])
const rows = ref<TransactionRow[]>([])
const persistedSummary = ref<MonthlySummary | null>(null)
const loading = ref(true)
const saving = ref(false)
const exporting = ref(false)
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const exportError = ref<string | null>(null)
const rowErrors = reactive<Record<string, TransactionFieldErrors>>({})
const savedSnapshot = ref('[]')
const activeCell = ref<ActiveCell | null>(null)
const rowEditSnapshot = ref<RowEditSnapshot | null>(null)

let nextLocalId = 1

const editableFields: EditableField[] = [
  'date',
  'type',
  'categoryId',
  'paymentMethodId',
  'amount',
  'memo',
]

const monthInputValue = computed(() => `${period.year}-${pad2(period.month)}`)
const monthStartDate = computed(() => `${monthInputValue.value}-01`)
const monthEndDate = computed(() => {
  const lastDay = new Date(period.year, period.month, 0).getDate()
  return `${monthInputValue.value}-${pad2(lastDay)}`
})
const periodLabel = computed(() => `${period.year}年${period.month}月`)
const hasDirtyChanges = computed(() => currentSnapshot() !== savedSnapshot.value)
const activeRows = computed(() => rows.value.filter((row) => !row.deleted))
const enteredRows = computed(() => activeRows.value.filter((row) => !isBlankNewRow(row)))
const activeRow = computed(() => {
  const current = activeCell.value
  if (!current) {
    return null
  }
  return rows.value.find((row) => row.localKey === current.rowKey) ?? null
})
const categoryHeaderLabel = computed(() =>
  activeRow.value?.type === 'TRANSFER' ? '振替元' : 'カテゴリ',
)
const paymentMethodHeaderLabel = computed(() =>
  activeRow.value?.type === 'TRANSFER' ? '振替先' : '支払い方法',
)
const screenSummary = computed(() => {
  const totals = activeRows.value.reduce(
    (current, row) => {
      if (row.amount === '' || isBlankNewRow(row)) {
        return current
      }

      if (row.type === 'INCOME') {
        current.incomeTotal += Number(row.amount)
      } else if (row.type === 'EXPENSE') {
        current.expenseTotal += Number(row.amount)
      }

      current.balance = current.incomeTotal - current.expenseTotal
      return current
    },
    {
      incomeTotal: 0,
      expenseTotal: 0,
      balance: 0,
    },
  )

  return totals
})

let autoSaveTimer: number | undefined
let autoSaveRequested = false

onMounted(() => {
  void loadMonth()
})

async function loadMonth(): Promise<void> {
  loading.value = true
  loadError.value = null
  saveError.value = null
  clearRowErrors()

  try {
    const [
      categoryItems,
      paymentMethodItems,
      transferAccountItems,
      transactionItems,
      monthlySummary,
    ] = await Promise.all([
      getCategories(),
      getPaymentMethods(),
      getTransferAccounts(),
      getTransactions(period.year, period.month),
      getMonthlySummary(period.year, period.month),
    ])

    categories.value = sortCategories(categoryItems)
    paymentMethods.value = sortPaymentMethods(paymentMethodItems)
    transferAccounts.value = sortTransferAccounts(transferAccountItems)
    rows.value = transactionItems.map(toRow)
    ensureTrailingBlankRow()
    persistedSummary.value = monthlySummary
    savedSnapshot.value = currentSnapshot()
  } catch (error) {
    loadError.value = toMessage(error)
  } finally {
    loading.value = false
  }
}

async function autoSaveMonth(): Promise<void> {
  if (loading.value) {
    return
  }

  if (saving.value) {
    autoSaveRequested = true
    return
  }

  if (!hasDirtyChanges.value) {
    return
  }

  const entries = buildSaveEntries()
  saveError.value = null
  clearRowErrors()

  if (!validateEntries(entries)) {
    saveError.value = '入力内容に誤りがあります'
    focusFirstError(entries)
    return
  }

  const includesNewRows = entries.some((entry) => entry.row.id === null)
  const savedEntriesSnapshot = JSON.stringify(entries.map((entry) => entry.request))
  const focusRestoreTarget = createFocusRestoreTarget(entries)
  saving.value = true

  try {
    await saveMonthlyTransactions(
      period.year,
      period.month,
      entries.map((entry) => entry.request),
    )
    if (includesNewRows) {
      savedSnapshot.value = await syncSavedRows(entries)
      restoreFocus(focusRestoreTarget)
    } else {
      persistedSummary.value = await getMonthlySummary(period.year, period.month)
      savedSnapshot.value = savedEntriesSnapshot
      ensureTrailingBlankRow()
    }
  } catch (error) {
    if (error instanceof ApiError) {
      applyApiErrors(error, entries)
      focusFirstError(entries)
    }
    saveError.value = toMessage(error)
  } finally {
    saving.value = false
    if (autoSaveRequested) {
      autoSaveRequested = false
      scheduleAutoSave()
    }
  }
}

async function exportCsv(): Promise<void> {
  if (loading.value || saving.value || exporting.value) {
    return
  }

  exportError.value = null
  if (hasDirtyChanges.value) {
    await autoSaveMonth()
    if (hasDirtyChanges.value) {
      return
    }
  }

  exporting.value = true
  try {
    const csv = await exportMonthlyTransactions(period.year, period.month)
    const url = URL.createObjectURL(csv)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'kakeibo-' + monthInputValue.value + '.csv'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    exportError.value = toMessage(error)
  } finally {
    exporting.value = false
  }
}

function scheduleAutoSave(delay = 700): void {
  if (autoSaveTimer !== undefined) {
    window.clearTimeout(autoSaveTimer)
  }

  autoSaveTimer = window.setTimeout(() => {
    autoSaveTimer = undefined
    void autoSaveMonth()
  }, delay)
}

function copyRow(row: TransactionRow): void {
  const copiedRow = createCopiedRow(row)
  const rowIndex = rows.value.findIndex((current) => current.localKey === row.localKey)

  if (rowIndex === -1) {
    rows.value = [...rows.value, copiedRow]
  } else {
    rows.value = [
      ...rows.value.slice(0, rowIndex + 1),
      copiedRow,
      ...rows.value.slice(rowIndex + 1),
    ]
  }

  ensureTrailingBlankRow()
  focusCell(copiedRow.localKey, 'date')
  scheduleAutoSave()
}

function deleteRow(row: TransactionRow): void {
  clearRowError(row)

  rows.value = rows.value.filter((current) => current.localKey !== row.localKey)
  ensureTrailingBlankRow()
  if (row.id !== null) {
    scheduleAutoSave(0)
  }
}

function handleTypeChange(row: TransactionRow): void {
  clearFieldError(row, 'type')

  if (row.type === 'TRANSFER') {
    if (!isTransferAccountId(row.categoryId)) {
      row.categoryId = defaultTransferAccountId()
    }
    if (!isTransferAccountId(row.paymentMethodId)) {
      row.paymentMethodId = defaultTransferAccountId()
    }
  } else {
    const selectedCategory = categories.value.find((category) => category.id === row.categoryId)
    if (selectedCategory?.type !== row.type) {
      row.categoryId = defaultCategoryId(row.type)
    }
    if (!isPaymentMethodId(row.paymentMethodId)) {
      row.paymentMethodId = defaultPaymentMethodId()
    }
  }

  handleCellInput(row, 'type')
}

function handleMonthInput(event: Event): void {
  const input = event.target as HTMLInputElement
  const nextValue = input.value
  if (!nextValue) {
    input.value = monthInputValue.value
    return
  }

  if (!confirmDiscardChanges()) {
    input.value = monthInputValue.value
    return
  }

  const [year, month] = nextValue.split('-').map(Number)
  period.year = year
  period.month = month
  void loadMonth()
}

function moveMonth(offset: number): void {
  if (!confirmDiscardChanges()) {
    return
  }

  const nextDate = new Date(period.year, period.month - 1 + offset, 1)
  period.year = nextDate.getFullYear()
  period.month = nextDate.getMonth() + 1
  void loadMonth()
}

function categoriesForType(type: TransactionType): Category[] {
  return categories.value.filter((category) => category.type === type)
}

function categoryOptions(row: TransactionRow): Category[] | TransferAccount[] {
  if (row.type === 'TRANSFER') {
    return transferAccounts.value
  }
  return categoriesForType(row.type)
}

function paymentMethodOptions(row: TransactionRow): PaymentMethod[] | TransferAccount[] {
  if (row.type === 'TRANSFER') {
    return transferAccounts.value
  }
  return paymentMethods.value
}

function startCellEdit(row: TransactionRow, field: EditableField): void {
  activeCell.value = {
    rowKey: row.localKey,
    field,
  }
  rowEditSnapshot.value = {
    date: row.date,
    type: row.type,
    categoryId: row.categoryId,
    paymentMethodId: row.paymentMethodId,
    amount: row.amount,
    memo: row.memo,
    deleted: row.deleted,
  }
}

function handleCellInput(row: TransactionRow, field: TransactionField): void {
  clearFieldError(row, field)
  ensureTrailingBlankRow()
  scheduleAutoSave()
}

function handleCellKeydown(
  event: KeyboardEvent,
  row: TransactionRow,
  field: EditableField,
): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    restoreEditingRow(row)
    clearRowError(row)
    return
  }

  if (event.key === 'Tab') {
    event.preventDefault()
    focusAdjacentCell(row, field, event.shiftKey ? -1 : 1)
  }
}

function isActiveCell(row: TransactionRow, field: EditableField): boolean {
  return activeCell.value?.rowKey === row.localKey && activeCell.value.field === field
}

function clearFieldError(row: TransactionRow, field: TransactionField): void {
  if (rowErrors[row.localKey]) {
    delete rowErrors[row.localKey][field]
  }
}

function isFieldDisabled(row: TransactionRow, field: EditableField): boolean {
  return (
    row.deleted ||
    loading.value ||
    (field === 'memo' && row.id === null && !isAmountLikelyValid(row))
  )
}

function isAmountLikelyValid(row: TransactionRow): boolean {
  return typeof row.amount === 'number' && Number.isInteger(row.amount) && row.amount >= 1
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('ja-JP', {
    style: 'currency',
    currency: 'JPY',
    maximumFractionDigits: 0,
  }).format(value)
}

function persistedSummaryText(): string {
  if (!persistedSummary.value) {
    return '未取得'
  }

  return [
    `収入 ${formatCurrency(persistedSummary.value.incomeTotal)}`,
    `支出 ${formatCurrency(persistedSummary.value.expenseTotal)}`,
    `差額 ${formatCurrency(persistedSummary.value.balance)}`,
  ].join(' / ')
}

function buildSaveEntries(sourceRows: TransactionRow[] = rows.value): SaveEntry[] {
  return sourceRows
    .filter((row) => !row.deleted)
    .filter((row) => !isBlankNewRow(row))
    .map((row, index) => ({
      row,
      request: toRequest(row, index * 10),
    }))
}

function validateEntries(entries: SaveEntry[]): boolean {
  let valid = true

  entries.forEach((entry) => {
    const errors: TransactionFieldErrors = {}
    const { row, request } = entry

    if (!request.date) {
      errors.date = '日付を入力してください'
    } else if (!isValidDateString(request.date)) {
      errors.date = '日付の形式が不正です'
    } else if (!isDateInCurrentMonth(request.date)) {
      errors.date = '対象月の日付を入力してください'
    }

    if (!request.type) {
      errors.type = '種別を選択してください'
    }

    if (request.type === 'TRANSFER') {
      const selectedTransferSource =
        request.categoryId === null
          ? undefined
          : transferAccounts.value.find((account) => account.id === request.categoryId)

      if (request.categoryId === null) {
        errors.categoryId = '振替元を選択してください'
      } else if (!selectedTransferSource) {
        errors.categoryId = '振替元が見つかりません'
      }

      const selectedTransferDestination =
        request.paymentMethodId === null
          ? undefined
          : transferAccounts.value.find((account) => account.id === request.paymentMethodId)

      if (request.paymentMethodId === null) {
        errors.paymentMethodId = '振替先を選択してください'
      } else if (!selectedTransferDestination) {
        errors.paymentMethodId = '振替先が見つかりません'
      }
    } else {
      const selectedCategory =
        request.categoryId === null
          ? undefined
          : categories.value.find((category) => category.id === request.categoryId)

      if (request.categoryId === null) {
        errors.categoryId = 'カテゴリを選択してください'
      } else if (!selectedCategory) {
        errors.categoryId = 'カテゴリが見つかりません'
      } else if (selectedCategory.type !== request.type) {
        errors.categoryId = '種別に合うカテゴリを選択してください'
      }

      const selectedPaymentMethod =
        request.paymentMethodId === null
          ? undefined
          : paymentMethods.value.find(
              (paymentMethod) => paymentMethod.id === request.paymentMethodId,
            )

      if (request.paymentMethodId === null) {
        errors.paymentMethodId = '支払い方法を選択してください'
      } else if (!selectedPaymentMethod) {
        errors.paymentMethodId = '支払い方法が見つかりません'
      }
    }

    if (request.amount === null) {
      errors.amount = '金額を入力してください'
    } else if (!Number.isInteger(request.amount) || request.amount < 1) {
      errors.amount = '金額は1以上の整数で入力してください'
    }

    if ((request.memo?.length ?? 0) > 500) {
      errors.memo = 'メモは500文字以内で入力してください'
    }

    if (Object.keys(errors).length > 0) {
      rowErrors[row.localKey] = errors
      valid = false
    }
  })

  return valid
}

function toRequest(row: TransactionRow, displayOrder: number): TransactionMonthlySaveRequest {
  return {
    id: row.id,
    date: row.date || null,
    type: row.type,
    categoryId: row.categoryId === '' ? null : row.categoryId,
    paymentMethodId: row.paymentMethodId === '' ? null : row.paymentMethodId,
    amount: row.amount === '' ? null : Number(row.amount),
    memo: row.memo.trim() === '' ? null : row.memo.trim(),
    displayOrder,
  }
}

async function syncSavedRows(entries: SaveEntry[]): Promise<string> {
  const [transactionItems, monthlySummary] = await Promise.all([
    getTransactions(period.year, period.month),
    getMonthlySummary(period.year, period.month),
  ])
  const matchedTransactionIds = new Set<number>()

  entries
    .filter((entry) => entry.row.id === null)
    .forEach((entry) => {
      const transaction = transactionItems.find(
        (candidate) =>
          !matchedTransactionIds.has(candidate.id) &&
          transactionMatchesRequest(candidate, entry.request),
      )
      if (transaction) {
        entry.row.id = transaction.id
        matchedTransactionIds.add(transaction.id)
      }
    })

  persistedSummary.value = monthlySummary
  ensureTrailingBlankRow()

  return JSON.stringify(
    entries.map((entry) => ({
      ...entry.request,
      id: entry.row.id ?? entry.request.id,
    })),
  )
}

function transactionMatchesRequest(
  transaction: Transaction,
  request: TransactionMonthlySaveRequest,
): boolean {
  return (
    transaction.date === request.date &&
    transaction.type === request.type &&
    transaction.categoryId === request.categoryId &&
    transaction.paymentMethodId === request.paymentMethodId &&
    transaction.amount === request.amount &&
    (transaction.memo ?? null) === (request.memo ?? null) &&
    transaction.displayOrder === request.displayOrder
  )
}

function currentSnapshot(sourceRows: TransactionRow[] = rows.value): string {
  return JSON.stringify(buildSaveEntries(sourceRows).map((entry) => entry.request))
}

function confirmDiscardChanges(): boolean {
  if (!hasDirtyChanges.value) {
    return true
  }

  return window.confirm('未保存の変更があります。破棄して月を移動しますか？')
}

function applyApiErrors(error: ApiError, entries: SaveEntry[]): void {
  error.errors.forEach((fieldError) => {
    const match = fieldError.field.match(/^\[(\d+)]\.(.+)$/)
    if (!match) {
      return
    }

    const index = Number(match[1])
    const field = match[2]
    const entry = entries[index]
    if (!entry || !isTransactionField(field)) {
      return
    }

    rowErrors[entry.row.localKey] = {
      ...rowErrors[entry.row.localKey],
      [field]: fieldError.message,
    }
  })
}

function focusFirstError(entries: SaveEntry[]): void {
  const firstError = entries
    .map((entry) => {
      const field = editableFields.find((candidate) => rowErrors[entry.row.localKey]?.[candidate])
      return field
        ? {
            row: entry.row,
            field,
          }
        : null
    })
    .find((error) => error !== null)

  if (firstError) {
    focusCell(firstError.row.localKey, firstError.field)
  }
}

function clearRowErrors(): void {
  Object.keys(rowErrors).forEach((key) => {
    delete rowErrors[key]
  })
}

function clearRowError(row: TransactionRow): void {
  delete rowErrors[row.localKey]
}

function createFocusRestoreTarget(entries: SaveEntry[]): FocusRestoreTarget | null {
  const current = activeCell.value
  if (!current) {
    return null
  }

  const entryIndex = entries.findIndex((entry) => entry.row.localKey === current.rowKey)
  if (entryIndex !== -1) {
    return {
      field: current.field,
      entryIndex,
    }
  }

  const row = rows.value.find((currentRow) => currentRow.localKey === current.rowKey)
  if (row && isBlankNewRow(row)) {
    return {
      field: current.field,
      blankRow: true,
    }
  }

  return null
}

function restoreFocus(target: FocusRestoreTarget | null): void {
  if (!target) {
    return
  }

  const row = target.blankRow
    ? rows.value.find((current) => current.id === null && isBlankNewRow(current))
    : target.entryIndex === undefined
      ? undefined
      : rows.value[target.entryIndex]

  if (row && !isFieldDisabled(row, target.field)) {
    focusCell(row.localKey, target.field)
  }
}

function toRow(transaction: Transaction): TransactionRow {
  return {
    localKey: createLocalKey(),
    id: transaction.id,
    date: transaction.date,
    type: transaction.type,
    categoryId: transaction.categoryId,
    paymentMethodId: transaction.paymentMethodId,
    amount: transaction.amount,
    memo: transaction.memo ?? '',
    deleted: false,
  }
}

function createEmptyRow(): TransactionRow {
  return {
    localKey: createLocalKey(),
    id: null,
    date: defaultNewRowDate(),
    type: 'EXPENSE',
    categoryId: defaultCategoryId('EXPENSE'),
    paymentMethodId: defaultPaymentMethodId(),
    amount: '',
    memo: '',
    deleted: false,
  }
}

function createCopiedRow(row: TransactionRow): TransactionRow {
  const copiedRow: TransactionRow = {
    localKey: createLocalKey(),
    id: null,
    date: row.date,
    type: row.type,
    categoryId: row.categoryId,
    paymentMethodId: row.paymentMethodId,
    amount: row.amount,
    memo: row.memo,
    deleted: false,
  }
  return copiedRow
}

function restoreEditingRow(row: TransactionRow): void {
  const snapshot = rowEditSnapshot.value
  if (!snapshot) {
    return
  }

  row.date = snapshot.date
  row.type = snapshot.type
  row.categoryId = snapshot.categoryId
  row.paymentMethodId = snapshot.paymentMethodId
  row.amount = snapshot.amount
  row.memo = snapshot.memo
  row.deleted = snapshot.deleted
  scheduleAutoSave()
}

function focusAdjacentCell(row: TransactionRow, field: EditableField, direction: 1 | -1): void {
  const editableRows = rows.value.filter((current) => !current.deleted)
  const rowIndex = editableRows.findIndex((current) => current.localKey === row.localKey)
  const fieldIndex = editableFields.indexOf(field)
  if (rowIndex === -1 || fieldIndex === -1) {
    return
  }

  let nextRowIndex = rowIndex
  let nextFieldIndex = fieldIndex
  const maxAttempts = (editableRows.length + 1) * editableFields.length

  for (let attempts = 0; attempts < maxAttempts; attempts += 1) {
    nextFieldIndex += direction

    if (nextFieldIndex >= editableFields.length) {
      nextFieldIndex = 0
      nextRowIndex += 1
    } else if (nextFieldIndex < 0) {
      nextFieldIndex = editableFields.length - 1
      nextRowIndex -= 1
    }

    if (nextRowIndex < 0) {
      return
    }

    if (nextRowIndex >= editableRows.length) {
      const lastRow = editableRows[editableRows.length - 1]
      if (!lastRow || lastRow.id === null) {
        return
      }

      const newRow = createEmptyRow()
      rows.value = [...rows.value, newRow]
      editableRows.push(newRow)
    }

    const nextRow = editableRows[nextRowIndex]
    const nextField = editableFields[nextFieldIndex]
    if (!isFieldDisabled(nextRow, nextField)) {
      focusCell(nextRow.localKey, nextField)
      return
    }
  }
}

function focusCell(rowKey: string, field: EditableField): void {
  window.requestAnimationFrame(() => {
    document
      .querySelector<HTMLElement>(`[data-cell-key="${rowKey}"][data-cell-field="${field}"]`)
      ?.focus()
  })
}

function ensureTrailingBlankRow(): void {
  const editableRows = rows.value.filter((row) => !row.deleted)
  if (editableRows.some(isUnsavedEnteredRow)) {
    const blankRowKeys = new Set(
      editableRows.filter((row) => isBlankNewRow(row)).map((row) => row.localKey),
    )
    if (blankRowKeys.size > 0) {
      rows.value = rows.value.filter((row) => row.deleted || !blankRowKeys.has(row.localKey))
    }
    return
  }

  const lastRow = editableRows[editableRows.length - 1]
  if (!lastRow || !isBlankNewRow(lastRow)) {
    rows.value = [...rows.value, createEmptyRow()]
  }
}

function createLocalKey(): string {
  const key = `row-${nextLocalId}`
  nextLocalId += 1
  return key
}

function isBlankNewRow(row: TransactionRow): boolean {
  return row.id === null && row.amount === '' && row.memo.trim() === ''
}

function isUnsavedEnteredRow(row: TransactionRow): boolean {
  return row.id === null && !isBlankNewRow(row)
}

function defaultCategoryId(type: TransactionType): number | '' {
  if (type === 'TRANSFER') {
    return defaultTransferAccountId()
  }
  return categoriesForType(type)[0]?.id ?? ''
}

function defaultPaymentMethodId(): number | '' {
  return paymentMethods.value[0]?.id ?? ''
}

function defaultTransferAccountId(): number | '' {
  return transferAccounts.value[0]?.id ?? ''
}

function isTransferAccountId(value: number | ''): boolean {
  return value !== '' && transferAccounts.value.some((account) => account.id === value)
}

function isPaymentMethodId(value: number | ''): boolean {
  return value !== '' && paymentMethods.value.some((paymentMethod) => paymentMethod.id === value)
}

function defaultNewRowDate(): string {
  const todayValue = formatDate(today)
  if (todayValue >= monthStartDate.value && todayValue <= monthEndDate.value) {
    return todayValue
  }

  return monthStartDate.value
}

function formatDate(value: Date): string {
  return [
    value.getFullYear(),
    pad2(value.getMonth() + 1),
    pad2(value.getDate()),
  ].join('-')
}

function isValidDateString(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) {
    return false
  }

  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(year, month - 1, day)
  return (
    date.getFullYear() === year &&
    date.getMonth() === month - 1 &&
    date.getDate() === day
  )
}

function isDateInCurrentMonth(value: string): boolean {
  return value >= monthStartDate.value && value <= monthEndDate.value
}

function isTransactionField(field: string): field is TransactionField {
  return (
    field === 'id' ||
    field === 'date' ||
    field === 'type' ||
    field === 'categoryId' ||
    field === 'paymentMethodId' ||
    field === 'amount' ||
    field === 'memo' ||
    field === 'displayOrder'
  )
}

function sortCategories(items: Category[]): Category[] {
  return [...items].sort((left, right) => {
    const typeOrder = left.type.localeCompare(right.type)
    if (typeOrder !== 0) {
      return typeOrder
    }

    const displayOrder = left.displayOrder - right.displayOrder
    if (displayOrder !== 0) {
      return displayOrder
    }

    return left.id - right.id
  })
}

function sortPaymentMethods(items: PaymentMethod[]): PaymentMethod[] {
  return [...items].sort((left, right) => {
    const displayOrder = left.displayOrder - right.displayOrder
    if (displayOrder !== 0) {
      return displayOrder
    }

    return left.id - right.id
  })
}

function sortTransferAccounts(items: TransferAccount[]): TransferAccount[] {
  return [...items].sort((left, right) => {
    const displayOrder = left.displayOrder - right.displayOrder
    if (displayOrder !== 0) {
      return displayOrder
    }

    return left.id - right.id
  })
}

function toMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  return '通信に失敗しました'
}

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}
</script>

<template>
  <section class="page-heading">
    <h1>家計簿入力</h1>
  </section>

  <section class="status-card">
    <div class="month-toolbar">
      <button
        type="button"
        class="secondary-button"
        :disabled="loading || saving"
        @click="moveMonth(-1)"
      >
        前月
      </button>

      <label class="month-picker">
        <span>対象月</span>
        <input
          type="month"
          required
          :value="monthInputValue"
          :disabled="loading || saving"
          @change="handleMonthInput"
        >
      </label>

      <button
        type="button"
        class="secondary-button"
        :disabled="loading || saving"
        @click="moveMonth(1)"
      >
        次月
      </button>
    </div>

    <div class="summary-grid" aria-label="月次集計">
      <div class="summary-card expense">
        <span>支出合計</span>
        <strong>{{ formatCurrency(screenSummary.expenseTotal) }}</strong>
      </div>
      <div class="summary-card income">
        <span>収入合計</span>
        <strong>{{ formatCurrency(screenSummary.incomeTotal) }}</strong>
      </div>
      <div class="summary-card balance">
        <span>差額</span>
        <strong>{{ formatCurrency(screenSummary.balance) }}</strong>
      </div>
    </div>

    <p class="summary-note">
      API集計: {{ persistedSummaryText() }}
    </p>
  </section>

  <section class="toolbar transaction-toolbar">
    <div class="toolbar-status">
      <span class="count-badge">{{ periodLabel }}</span>
    </div>
    <button
      type="button"
      class="secondary-button"
      :disabled="loading || saving || exporting"
      @click="exportCsv"
    >
      {{ exporting ? 'CSV出力中...' : 'CSV出力' }}
    </button>
  </section>

  <p v-if="loadError" class="message error">{{ loadError }}</p>
  <p v-if="saveError" class="message error">{{ saveError }}</p>
  <p v-if="exportError" class="message error">{{ exportError }}</p>

  <section class="category-section">
    <div class="section-heading">
      <div>
        <p class="eyebrow">入力表</p>
        <h2>{{ periodLabel }}の家計簿</h2>
      </div>
      <span class="count-badge">登録済み {{ enteredRows.length }}件</span>
    </div>

    <p v-if="!loading && !loadError && categories.length === 0" class="message error">
      カテゴリが未登録です。先にカテゴリ管理画面でカテゴリを登録してください。
    </p>
    <p v-if="!loading && !loadError && paymentMethods.length === 0" class="message error">
      支払い方法が未登録です。先に支払い方法管理画面で支払い方法を登録してください。
    </p>
    <p v-if="!loading && !loadError && transferAccounts.length === 0" class="message error">
      振替元・振替先が未登録です。先に振替管理画面で登録してください。
    </p>

    <div class="table-wrap">
      <table class="transaction-table">
        <thead>
          <tr>
            <th scope="col">日付</th>
            <th scope="col">種別</th>
            <th scope="col">{{ categoryHeaderLabel }}</th>
            <th scope="col">{{ paymentMethodHeaderLabel }}</th>
            <th scope="col">金額</th>
            <th scope="col">メモ</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="rows.length === 0">
            <td colspan="7" class="empty-cell">この月の家計簿データはまだありません。</td>
          </tr>
          <tr
            v-for="row in rows"
            :key="row.localKey"
            :class="{ 'deleted-row': row.deleted }"
          >
            <td>
              <input
                v-model="row.date"
                type="date"
                required
                :class="{
                  'active-cell': isActiveCell(row, 'date'),
                  'cell-error': !!rowErrors[row.localKey]?.date,
                }"
                :data-cell-key="row.localKey"
                data-cell-field="date"
                :min="monthStartDate"
                :max="monthEndDate"
                :disabled="isFieldDisabled(row, 'date')"
                @focus="startCellEdit(row, 'date')"
                @input="handleCellInput(row, 'date')"
                @keydown="handleCellKeydown($event, row, 'date')"
              >
              <small v-if="rowErrors[row.localKey]?.date" class="field-error">
                {{ rowErrors[row.localKey]?.date }}
              </small>
            </td>
            <td>
              <select
                v-model="row.type"
                :class="{
                  'active-cell': isActiveCell(row, 'type'),
                  'cell-error': !!rowErrors[row.localKey]?.type,
                }"
                :data-cell-key="row.localKey"
                data-cell-field="type"
                :disabled="isFieldDisabled(row, 'type')"
                @focus="startCellEdit(row, 'type')"
                @change="handleTypeChange(row)"
                @keydown="handleCellKeydown($event, row, 'type')"
              >
                <option value="EXPENSE">支出</option>
                <option value="INCOME">収入</option>
                <option value="TRANSFER">振替</option>
              </select>
              <small v-if="rowErrors[row.localKey]?.type" class="field-error">
                {{ rowErrors[row.localKey]?.type }}
              </small>
            </td>
            <td>
              <select
                v-model="row.categoryId"
                :class="{
                  'active-cell': isActiveCell(row, 'categoryId'),
                  'cell-error': !!rowErrors[row.localKey]?.categoryId,
                }"
                :data-cell-key="row.localKey"
                data-cell-field="categoryId"
                :disabled="isFieldDisabled(row, 'categoryId')"
                @focus="startCellEdit(row, 'categoryId')"
                @change="handleCellInput(row, 'categoryId')"
                @keydown="handleCellKeydown($event, row, 'categoryId')"
              >
                <option
                  v-for="option in categoryOptions(row)"
                  :key="option.id"
                  :value="option.id"
                >
                  {{ option.name }}
                </option>
              </select>
              <small v-if="rowErrors[row.localKey]?.categoryId" class="field-error">
                {{ rowErrors[row.localKey]?.categoryId }}
              </small>
            </td>
            <td>
              <select
                v-model="row.paymentMethodId"
                :class="{
                  'active-cell': isActiveCell(row, 'paymentMethodId'),
                  'cell-error': !!rowErrors[row.localKey]?.paymentMethodId,
                }"
                :data-cell-key="row.localKey"
                data-cell-field="paymentMethodId"
                :disabled="isFieldDisabled(row, 'paymentMethodId')"
                @focus="startCellEdit(row, 'paymentMethodId')"
                @change="handleCellInput(row, 'paymentMethodId')"
                @keydown="handleCellKeydown($event, row, 'paymentMethodId')"
              >
                <option
                  v-for="option in paymentMethodOptions(row)"
                  :key="option.id"
                  :value="option.id"
                >
                  {{ option.name }}
                </option>
              </select>
              <small v-if="rowErrors[row.localKey]?.paymentMethodId" class="field-error">
                {{ rowErrors[row.localKey]?.paymentMethodId }}
              </small>
            </td>
            <td>
              <input
                v-model.number="row.amount"
                type="number"
                :class="{
                  'active-cell': isActiveCell(row, 'amount'),
                  'cell-error': !!rowErrors[row.localKey]?.amount,
                }"
                :data-cell-key="row.localKey"
                data-cell-field="amount"
                min="1"
                inputmode="numeric"
                :disabled="isFieldDisabled(row, 'amount')"
                @focus="startCellEdit(row, 'amount')"
                @input="handleCellInput(row, 'amount')"
                @keydown="handleCellKeydown($event, row, 'amount')"
              >
              <small v-if="rowErrors[row.localKey]?.amount" class="field-error">
                {{ rowErrors[row.localKey]?.amount }}
              </small>
            </td>
            <td>
              <textarea
                v-model="row.memo"
                class="memo-textarea"
                :class="{
                  'active-cell': isActiveCell(row, 'memo'),
                  'cell-error': !!rowErrors[row.localKey]?.memo,
                }"
                :data-cell-key="row.localKey"
                data-cell-field="memo"
                maxlength="500"
                wrap="soft"
                :disabled="isFieldDisabled(row, 'memo')"
                @focus="startCellEdit(row, 'memo')"
                @input="handleCellInput(row, 'memo')"
                @keydown="handleCellKeydown($event, row, 'memo')"
              />
              <small v-if="rowErrors[row.localKey]?.memo" class="field-error">
                {{ rowErrors[row.localKey]?.memo }}
              </small>
            </td>
            <td class="table-actions-cell">
              <small v-if="rowErrors[row.localKey]?.displayOrder" class="field-error">
                {{ rowErrors[row.localKey]?.displayOrder }}
              </small>
              <small v-if="rowErrors[row.localKey]?.id" class="field-error">
                {{ rowErrors[row.localKey]?.id }}
              </small>
              <button
                type="button"
                class="danger-button"
                :disabled="loading || saving"
                @click="deleteRow(row)"
              >
                削除
              </button>
              <button
                v-if="!row.deleted"
                type="button"
                class="secondary-button"
                :disabled="loading || saving || isBlankNewRow(row)"
                @click="copyRow(row)"
              >
                コピー
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
