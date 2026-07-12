<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ApiError, toMessage } from '@/api/http'
import {
  createTransaction,
  deleteTransaction,
  getCategories,
  getMonthlySummary,
  getPaymentMethods,
  getTransactions,
  getTransferAccounts,
  updateTransaction,
  type Category,
  type MonthlySummary,
  type PaymentMethod,
  type Transaction,
  type TransactionSaveRequest,
  type TransactionType,
  type TransferAccount,
} from '@/api/kakeibo'
import { compareByDisplayOrder, compareCategories } from '@/masters'
import { formatDate, pad2 } from '@/transactions/dates'
import {
  createCopiedRow,
  createEmptyRow,
  isBlankNewRow,
  isTransactionField,
  toRequest,
  toRow,
  type RowDefaults,
  type TransactionField,
  type TransactionFieldErrors,
  type TransactionRow,
} from '@/transactions/rowModel'
import { validateEntries, type ValidationContext } from '@/transactions/validation'

type SortField = 'date' | 'type' | 'category' | 'paymentMethod' | 'amount'
type SortDirection = 'asc' | 'desc'

const today = new Date()
const period = reactive({
  year: today.getFullYear(),
  month: today.getMonth() + 1,
})

const categories = ref<Category[]>([])
const paymentMethods = ref<PaymentMethod[]>([])
const transferAccounts = ref<TransferAccount[]>([])
const rows = ref<TransactionRow[]>([])
const newRow = reactive(
  createEmptyRow({
    date: formatDate(today),
    categoryId: '',
    paymentMethodId: '',
  }),
)
const persistedSummary = ref<MonthlySummary | null>(null)
const savedRowSnapshots = ref<Record<string, string>>({})
const loading = ref(true)
const saving = ref(false)
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const rowErrors = reactive<Record<string, TransactionFieldErrors>>({})
const activeType = ref<TransactionType>('EXPENSE')
const sort = reactive<{ field: SortField; direction: SortDirection }>({
  field: 'date',
  direction: 'asc',
})

const sheetOpen = ref(false)
const sheetMode = ref<'create' | 'edit'>('create')
const sheetRowKey = ref<string | null>(null)
const draft = reactive<TransactionRow>({
  localKey: 'draft',
  id: null,
  date: '',
  type: 'EXPENSE',
  categoryId: '',
  paymentMethodId: '',
  amount: '',
  memo: '',
  deleted: false,
})
const draftErrors = reactive<TransactionFieldErrors>({})

const monthInputValue = computed(() => `${period.year}-${pad2(period.month)}`)
const monthStartDate = computed(() => `${monthInputValue.value}-01`)
const monthEndDate = computed(() => {
  const lastDay = new Date(period.year, period.month, 0).getDate()
  return `${monthInputValue.value}-${pad2(lastDay)}`
})
const periodLabel = computed(() => `${period.year}年${period.month}月`)
const enteredRows = computed(() => rows.value)
const categoryHeaderLabel = computed(() =>
  activeType.value === 'TRANSFER' ? '振替元' : 'カテゴリ',
)
const paymentMethodHeaderLabel = computed(() =>
  activeType.value === 'TRANSFER' ? '振替先' : '支払い方法',
)
const sortedRows = computed(() => [...rows.value].sort(compareRows))
const mobileEntries = computed(() =>
  [...rows.value].sort((left, right) => {
    if (left.date !== right.date) {
      return left.date < right.date ? 1 : -1
    }
    return (right.id ?? 0) - (left.id ?? 0)
  }),
)
const hasDirtyChanges = computed(
  () => !isBlankNewRow(newRow) || rows.value.some((row) => isRowDirty(row)),
)
const screenSummary = computed(() => {
  const totals = rows.value.reduce(
    (current, row) => {
      const amount = Number(row.amount)
      if (!Number.isInteger(amount) || amount < 1) {
        return current
      }
      if (row.type === 'INCOME') {
        current.incomeTotal += amount
      } else if (row.type === 'EXPENSE') {
        current.expenseTotal += amount
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

const nameCollator = new Intl.Collator('ja')

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

    categories.value = [...categoryItems].sort(compareCategories)
    paymentMethods.value = [...paymentMethodItems].sort(compareByDisplayOrder)
    transferAccounts.value = [...transferAccountItems].sort(compareByDisplayOrder)
    rows.value = transactionItems.map(toRow)
    captureSavedRowSnapshots()
    persistedSummary.value = monthlySummary
    resetNewRow()
    closeSheet()
  } catch (error) {
    loadError.value = toMessage(error)
  } finally {
    loading.value = false
  }
}

async function registerNewRow(): Promise<void> {
  if (loading.value || saving.value || !validateRow(newRow)) {
    return
  }

  saving.value = true
  saveError.value = null
  try {
    const saved = await createTransaction(period.year, period.month, toSaveRequest(newRow))
    const created = toRow(saved)
    rows.value = [...rows.value, created]
    captureSavedRow(created)
    await refreshPersistedSummary()
    resetNewRow()
    await focusNewRowDate()
  } catch (error) {
    applySaveError(error, newRow)
  } finally {
    saving.value = false
  }
}

async function updateRow(row: TransactionRow): Promise<void> {
  if (loading.value || saving.value || !isRowDirty(row) || !validateRow(row)) {
    return
  }

  saving.value = true
  saveError.value = null
  try {
    const saved = await updateTransaction(
      row.id ?? 0,
      period.year,
      period.month,
      toSaveRequest(row),
    )
    applyTransaction(row, saved)
    captureSavedRow(row)
    await refreshPersistedSummary()
  } catch (error) {
    applySaveError(error, row)
  } finally {
    saving.value = false
  }
}

async function deleteRow(row: TransactionRow): Promise<void> {
  if (loading.value || saving.value || row.id === null) {
    return
  }

  saving.value = true
  saveError.value = null
  try {
    await deleteTransaction(row.id, period.year, period.month)
    rows.value = rows.value.filter((current) => current.localKey !== row.localKey)
    delete savedRowSnapshots.value[row.localKey]
    clearRowError(row)
    await refreshPersistedSummary()
  } catch (error) {
    saveError.value = toMessage(error)
  } finally {
    saving.value = false
  }
}

function copyRow(row: TransactionRow): void {
  if (loading.value || saving.value) {
    return
  }

  const copied = createCopiedRow(row)
  Object.assign(newRow, copied)
  activeType.value = newRow.type
  clearRowError(newRow)
  void focusNewRowDate()
}

function validationContext(): ValidationContext {
  return {
    categories: categories.value,
    paymentMethods: paymentMethods.value,
    transferAccounts: transferAccounts.value,
    monthStartDate: monthStartDate.value,
    monthEndDate: monthEndDate.value,
  }
}

function validateRow(row: TransactionRow): boolean {
  clearRowError(row)
  const request = toRequest(row, 0)
  const errors = validateEntries([{ row, request }], validationContext())[row.localKey]
  if (!errors) {
    return true
  }

  rowErrors[row.localKey] = errors
  saveError.value = '入力内容に誤りがあります'
  return false
}

function toSaveRequest(row: TransactionRow): TransactionSaveRequest {
  const request = toRequest(row, 0)
  return {
    date: request.date,
    type: row.type,
    categoryId: request.categoryId,
    paymentMethodId: request.paymentMethodId,
    amount: request.amount,
    memo: request.memo,
  }
}

function applyTransaction(row: TransactionRow, transaction: Transaction): void {
  row.id = transaction.id
  row.date = transaction.date
  row.type = transaction.type
  row.categoryId = transaction.categoryId
  row.paymentMethodId = transaction.paymentMethodId
  row.amount = transaction.amount
  row.memo = transaction.memo ?? ''
}

async function refreshPersistedSummary(): Promise<void> {
  persistedSummary.value = await getMonthlySummary(period.year, period.month)
}

function resetNewRow(): void {
  const previousKey = newRow.localKey
  Object.assign(newRow, createEmptyRow(rowDefaults()))
  delete rowErrors[previousKey]
  clearRowError(newRow)
  activeType.value = newRow.type
}

async function focusNewRowDate(): Promise<void> {
  await nextTick()
  document.querySelector<HTMLInputElement>('[data-new-row-field="date"]')?.focus()
}

function rowDefaults(): RowDefaults {
  return {
    date: defaultNewRowDate(),
    categoryId: defaultCategoryId('EXPENSE'),
    paymentMethodId: defaultPaymentMethodId(),
  }
}

function handleTypeChange(row: TransactionRow): void {
  activeType.value = row.type
  clearFieldError(row, 'type')
  applyTypeDefaults(row)
  clearFieldError(row, 'categoryId')
  clearFieldError(row, 'paymentMethodId')
}

function handleFieldInput(row: TransactionRow, field: TransactionField): void {
  activeType.value = row.type
  clearFieldError(row, field)
}

function applyTypeDefaults(row: TransactionRow): void {
  if (row.type === 'TRANSFER') {
    if (!isTransferAccountId(row.categoryId)) {
      row.categoryId = defaultTransferAccountId()
    }
    if (!isTransferAccountId(row.paymentMethodId)) {
      row.paymentMethodId = defaultTransferAccountId()
    }
    return
  }

  const selectedCategory = categories.value.find((category) => category.id === row.categoryId)
  if (selectedCategory?.type !== row.type) {
    row.categoryId = defaultCategoryId(row.type)
  }
  if (!isPaymentMethodId(row.paymentMethodId)) {
    row.paymentMethodId = defaultPaymentMethodId()
  }
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

function confirmDiscardChanges(): boolean {
  if (!hasDirtyChanges.value) {
    return true
  }
  return window.confirm('未保存の変更があります。破棄して月を移動しますか？')
}

function categoriesForType(type: TransactionType): Category[] {
  return categories.value.filter((category) => category.type === type)
}

function categoryOptions(row: TransactionRow): Category[] | TransferAccount[] {
  return row.type === 'TRANSFER' ? transferAccounts.value : categoriesForType(row.type)
}

function paymentMethodOptions(row: TransactionRow): PaymentMethod[] | TransferAccount[] {
  return row.type === 'TRANSFER' ? transferAccounts.value : paymentMethods.value
}

function isFieldDisabled(row: TransactionRow, field: TransactionField): boolean {
  return (
    loading.value ||
    saving.value ||
    (field === 'memo' && row.id === null && !isAmountLikelyValid(row))
  )
}

function isAmountLikelyValid(row: TransactionRow): boolean {
  return typeof row.amount === 'number' && Number.isInteger(row.amount) && row.amount >= 1
}

function defaultCategoryId(type: TransactionType): number | '' {
  return type === 'TRANSFER' ? defaultTransferAccountId() : (categoriesForType(type)[0]?.id ?? '')
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
  return todayValue >= monthStartDate.value && todayValue <= monthEndDate.value
    ? todayValue
    : monthStartDate.value
}

function toggleSort(field: SortField): void {
  if (sort.field === field) {
    sort.direction = sort.direction === 'asc' ? 'desc' : 'asc'
  } else {
    sort.field = field
    sort.direction = 'asc'
  }
}

function sortIcon(field: SortField): string {
  if (sort.field !== field) {
    return '⇅'
  }
  return sort.direction === 'asc' ? '↑' : '↓'
}

function sortButtonLabel(label: string, field: SortField): string {
  const nextDirection = sort.field === field && sort.direction === 'asc' ? '降順' : '昇順'
  return `${label}を${nextDirection}で並び替え`
}

function compareRows(left: TransactionRow, right: TransactionRow): number {
  const primary = compareBySortField(left, right)
  if (primary !== 0) {
    return sort.direction === 'asc' ? primary : -primary
  }
  if (left.date !== right.date) {
    return left.date < right.date ? -1 : 1
  }
  return (left.id ?? 0) - (right.id ?? 0)
}

function compareBySortField(left: TransactionRow, right: TransactionRow): number {
  switch (sort.field) {
    case 'date':
      return left.date.localeCompare(right.date)
    case 'type':
      return typeSortOrder(left.type) - typeSortOrder(right.type)
    case 'category':
      return nameCollator.compare(rowCategoryName(left), rowCategoryName(right))
    case 'paymentMethod':
      return nameCollator.compare(rowPaymentName(left), rowPaymentName(right))
    case 'amount':
      return Number(left.amount) - Number(right.amount)
  }
}

function typeSortOrder(type: TransactionType): number {
  if (type === 'INCOME') {
    return 1
  }
  if (type === 'TRANSFER') {
    return 2
  }
  return 0
}

function rowSnapshot(row: TransactionRow): string {
  return JSON.stringify(toSaveRequest(row))
}

function isRowDirty(row: TransactionRow): boolean {
  return savedRowSnapshots.value[row.localKey] !== rowSnapshot(row)
}

function captureSavedRowSnapshots(): void {
  savedRowSnapshots.value = Object.fromEntries(
    rows.value.map((row) => [row.localKey, rowSnapshot(row)]),
  )
}

function captureSavedRow(row: TransactionRow): void {
  savedRowSnapshots.value = {
    ...savedRowSnapshots.value,
    [row.localKey]: rowSnapshot(row),
  }
}

function applySaveError(error: unknown, row: TransactionRow): void {
  if (error instanceof ApiError) {
    applyApiErrors(error, row)
  }
  saveError.value = toMessage(error)
}

function applyApiErrors(error: ApiError, row: TransactionRow): void {
  error.errors.forEach((fieldError) => {
    const field = fieldError.field.replace(/^\[\d+\]\./, '')
    if (!isTransactionField(field)) {
      return
    }
    rowErrors[row.localKey] = {
      ...rowErrors[row.localKey],
      [field]: fieldError.message,
    }
  })
}

function clearRowErrors(): void {
  Object.keys(rowErrors).forEach((key) => delete rowErrors[key])
}

function clearRowError(row: TransactionRow): void {
  delete rowErrors[row.localKey]
}

function clearFieldError(row: TransactionRow, field: TransactionField): void {
  if (rowErrors[row.localKey]) {
    delete rowErrors[row.localKey][field]
  }
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

function typeLabel(type: TransactionType): string {
  if (type === 'INCOME') {
    return '収入'
  }
  if (type === 'TRANSFER') {
    return '振替'
  }
  return '支出'
}

function rowCategoryName(row: TransactionRow): string {
  if (row.type === 'TRANSFER') {
    return transferAccounts.value.find((account) => account.id === row.categoryId)?.name ?? ''
  }
  return categories.value.find((category) => category.id === row.categoryId)?.name ?? ''
}

function rowPaymentName(row: TransactionRow): string {
  if (row.type === 'TRANSFER') {
    return transferAccounts.value.find((account) => account.id === row.paymentMethodId)?.name ?? ''
  }
  return (
    paymentMethods.value.find((paymentMethod) => paymentMethod.id === row.paymentMethodId)?.name ??
    ''
  )
}

function clearDraftErrors(): void {
  Object.keys(draftErrors).forEach((key) => delete draftErrors[key as TransactionField])
}

function clearDraftError(field: TransactionField): void {
  delete draftErrors[field]
}

function resetDraft(source: TransactionRow | null): void {
  const base = source ?? createEmptyRow(rowDefaults())
  draft.id = source?.id ?? null
  draft.date = base.date
  draft.type = base.type
  draft.categoryId = base.categoryId
  draft.paymentMethodId = base.paymentMethodId
  draft.amount = base.amount
  draft.memo = base.memo
  draft.deleted = false
}

function openCreateSheet(): void {
  resetDraft(null)
  sheetMode.value = 'create'
  sheetRowKey.value = null
  clearDraftErrors()
  sheetOpen.value = true
}

function openEditSheet(row: TransactionRow): void {
  resetDraft(row)
  sheetMode.value = 'edit'
  sheetRowKey.value = row.localKey
  clearDraftErrors()
  activeType.value = row.type
  sheetOpen.value = true
}

function closeSheet(): void {
  sheetOpen.value = false
}

function changeDraftType(): void {
  clearDraftError('type')
  applyTypeDefaults(draft)
  clearDraftError('categoryId')
  clearDraftError('paymentMethodId')
}

async function submitSheet(): Promise<void> {
  clearDraftErrors()
  const request = toRequest(draft, 0)
  const found = validateEntries([{ row: draft, request }], validationContext())[draft.localKey]
  if (found) {
    Object.assign(draftErrors, found)
    return
  }
  if (saving.value) {
    return
  }

  saving.value = true
  saveError.value = null
  try {
    if (sheetMode.value === 'edit') {
      const target = rows.value.find((row) => row.localKey === sheetRowKey.value)
      if (!target || target.id === null) {
        return
      }
      const saved = await updateTransaction(
        target.id,
        period.year,
        period.month,
        toSaveRequest(draft),
      )
      applyTransaction(target, saved)
      captureSavedRow(target)
    } else {
      const saved = await createTransaction(period.year, period.month, toSaveRequest(draft))
      const created = toRow(saved)
      rows.value = [...rows.value, created]
      captureSavedRow(created)
    }
    await refreshPersistedSummary()
    closeSheet()
  } catch (error) {
    if (error instanceof ApiError) {
      error.errors.forEach((fieldError) => {
        const field = fieldError.field.replace(/^\[\d+\]\./, '')
        if (isTransactionField(field)) {
          draftErrors[field] = fieldError.message
        }
      })
    }
    saveError.value = toMessage(error)
  } finally {
    saving.value = false
  }
}

async function deleteFromSheet(): Promise<void> {
  const target = rows.value.find((row) => row.localKey === sheetRowKey.value)
  if (!target) {
    closeSheet()
    return
  }
  await deleteRow(target)
  if (!rows.value.some((row) => row.localKey === target.localKey)) {
    closeSheet()
  }
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
        />
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

    <p class="summary-note">API集計: {{ persistedSummaryText() }}</p>
  </section>

  <p v-if="loadError" class="message error">{{ loadError }}</p>
  <p v-if="saveError" class="message error">{{ saveError }}</p>

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
            <th scope="col">
              <span class="sort-header"
                >日付<button
                  type="button"
                  class="sort-button"
                  :aria-label="sortButtonLabel('日付', 'date')"
                  @click="toggleSort('date')"
                >
                  {{ sortIcon('date') }}
                </button></span
              >
            </th>
            <th scope="col">
              <span class="sort-header"
                >種別<button
                  type="button"
                  class="sort-button"
                  :aria-label="sortButtonLabel('種別', 'type')"
                  @click="toggleSort('type')"
                >
                  {{ sortIcon('type') }}
                </button></span
              >
            </th>
            <th scope="col">
              <span class="sort-header"
                >{{ categoryHeaderLabel
                }}<button
                  type="button"
                  class="sort-button"
                  :aria-label="sortButtonLabel(categoryHeaderLabel, 'category')"
                  @click="toggleSort('category')"
                >
                  {{ sortIcon('category') }}
                </button></span
              >
            </th>
            <th scope="col">
              <span class="sort-header"
                >{{ paymentMethodHeaderLabel
                }}<button
                  type="button"
                  class="sort-button"
                  :aria-label="sortButtonLabel(paymentMethodHeaderLabel, 'paymentMethod')"
                  @click="toggleSort('paymentMethod')"
                >
                  {{ sortIcon('paymentMethod') }}
                </button></span
              >
            </th>
            <th scope="col">
              <span class="sort-header"
                >金額<button
                  type="button"
                  class="sort-button"
                  :aria-label="sortButtonLabel('金額', 'amount')"
                  @click="toggleSort('amount')"
                >
                  {{ sortIcon('amount') }}
                </button></span
              >
            </th>
            <th scope="col">メモ</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr class="new-transaction-row">
            <td>
              <input
                v-model="newRow.date"
                type="date"
                required
                data-new-row-field="date"
                :class="{ 'cell-error': !!rowErrors[newRow.localKey]?.date }"
                :min="monthStartDate"
                :max="monthEndDate"
                :disabled="isFieldDisabled(newRow, 'date')"
                @focus="activeType = newRow.type"
                @input="handleFieldInput(newRow, 'date')"
              />
              <small v-if="rowErrors[newRow.localKey]?.date" class="field-error">{{
                rowErrors[newRow.localKey]?.date
              }}</small>
            </td>
            <td>
              <select
                v-model="newRow.type"
                :class="{ 'cell-error': !!rowErrors[newRow.localKey]?.type }"
                :disabled="isFieldDisabled(newRow, 'type')"
                @change="handleTypeChange(newRow)"
              >
                <option value="EXPENSE">支出</option>
                <option value="INCOME">収入</option>
                <option value="TRANSFER">振替</option>
              </select>
              <small v-if="rowErrors[newRow.localKey]?.type" class="field-error">{{
                rowErrors[newRow.localKey]?.type
              }}</small>
            </td>
            <td>
              <select
                v-model="newRow.categoryId"
                :class="{ 'cell-error': !!rowErrors[newRow.localKey]?.categoryId }"
                :disabled="isFieldDisabled(newRow, 'categoryId')"
                @focus="activeType = newRow.type"
                @change="handleFieldInput(newRow, 'categoryId')"
              >
                <option
                  v-for="option in categoryOptions(newRow)"
                  :key="option.id"
                  :value="option.id"
                >
                  {{ option.name }}
                </option>
              </select>
              <small v-if="rowErrors[newRow.localKey]?.categoryId" class="field-error">{{
                rowErrors[newRow.localKey]?.categoryId
              }}</small>
            </td>
            <td>
              <select
                v-model="newRow.paymentMethodId"
                :class="{ 'cell-error': !!rowErrors[newRow.localKey]?.paymentMethodId }"
                :disabled="isFieldDisabled(newRow, 'paymentMethodId')"
                @focus="activeType = newRow.type"
                @change="handleFieldInput(newRow, 'paymentMethodId')"
              >
                <option
                  v-for="option in paymentMethodOptions(newRow)"
                  :key="option.id"
                  :value="option.id"
                >
                  {{ option.name }}
                </option>
              </select>
              <small v-if="rowErrors[newRow.localKey]?.paymentMethodId" class="field-error">{{
                rowErrors[newRow.localKey]?.paymentMethodId
              }}</small>
            </td>
            <td>
              <input
                v-model.number="newRow.amount"
                type="number"
                min="1"
                inputmode="numeric"
                :class="{ 'cell-error': !!rowErrors[newRow.localKey]?.amount }"
                :disabled="isFieldDisabled(newRow, 'amount')"
                @focus="activeType = newRow.type"
                @input="handleFieldInput(newRow, 'amount')"
              />
              <small v-if="rowErrors[newRow.localKey]?.amount" class="field-error">{{
                rowErrors[newRow.localKey]?.amount
              }}</small>
            </td>
            <td>
              <textarea
                v-model="newRow.memo"
                class="memo-textarea"
                maxlength="500"
                wrap="soft"
                :class="{ 'cell-error': !!rowErrors[newRow.localKey]?.memo }"
                :disabled="isFieldDisabled(newRow, 'memo')"
                @focus="activeType = newRow.type"
                @input="handleFieldInput(newRow, 'memo')"
              />
              <small v-if="rowErrors[newRow.localKey]?.memo" class="field-error">{{
                rowErrors[newRow.localKey]?.memo
              }}</small>
            </td>
            <td class="table-actions-cell">
              <button type="button" :disabled="loading || saving" @click="registerNewRow">
                {{ saving ? '登録中...' : '登録' }}
              </button>
            </td>
          </tr>
          <tr v-if="!loading && sortedRows.length === 0">
            <td colspan="7" class="empty-cell">この月の家計簿データはまだありません。</td>
          </tr>
          <tr v-for="row in sortedRows" :key="row.localKey">
            <td>
              <input
                v-model="row.date"
                type="date"
                required
                :class="{ 'cell-error': !!rowErrors[row.localKey]?.date }"
                :min="monthStartDate"
                :max="monthEndDate"
                :disabled="isFieldDisabled(row, 'date')"
                @focus="activeType = row.type"
                @input="handleFieldInput(row, 'date')"
              />
              <small v-if="rowErrors[row.localKey]?.date" class="field-error">{{
                rowErrors[row.localKey]?.date
              }}</small>
            </td>
            <td>
              <select
                v-model="row.type"
                :class="{ 'cell-error': !!rowErrors[row.localKey]?.type }"
                :disabled="isFieldDisabled(row, 'type')"
                @change="handleTypeChange(row)"
              >
                <option value="EXPENSE">支出</option>
                <option value="INCOME">収入</option>
                <option value="TRANSFER">振替</option>
              </select>
              <small v-if="rowErrors[row.localKey]?.type" class="field-error">{{
                rowErrors[row.localKey]?.type
              }}</small>
            </td>
            <td>
              <select
                v-model="row.categoryId"
                :class="{ 'cell-error': !!rowErrors[row.localKey]?.categoryId }"
                :disabled="isFieldDisabled(row, 'categoryId')"
                @focus="activeType = row.type"
                @change="handleFieldInput(row, 'categoryId')"
              >
                <option v-for="option in categoryOptions(row)" :key="option.id" :value="option.id">
                  {{ option.name }}
                </option>
              </select>
              <small v-if="rowErrors[row.localKey]?.categoryId" class="field-error">{{
                rowErrors[row.localKey]?.categoryId
              }}</small>
            </td>
            <td>
              <select
                v-model="row.paymentMethodId"
                :class="{ 'cell-error': !!rowErrors[row.localKey]?.paymentMethodId }"
                :disabled="isFieldDisabled(row, 'paymentMethodId')"
                @focus="activeType = row.type"
                @change="handleFieldInput(row, 'paymentMethodId')"
              >
                <option
                  v-for="option in paymentMethodOptions(row)"
                  :key="option.id"
                  :value="option.id"
                >
                  {{ option.name }}
                </option>
              </select>
              <small v-if="rowErrors[row.localKey]?.paymentMethodId" class="field-error">{{
                rowErrors[row.localKey]?.paymentMethodId
              }}</small>
            </td>
            <td>
              <input
                v-model.number="row.amount"
                type="number"
                min="1"
                inputmode="numeric"
                :class="{ 'cell-error': !!rowErrors[row.localKey]?.amount }"
                :disabled="isFieldDisabled(row, 'amount')"
                @focus="activeType = row.type"
                @input="handleFieldInput(row, 'amount')"
              />
              <small v-if="rowErrors[row.localKey]?.amount" class="field-error">{{
                rowErrors[row.localKey]?.amount
              }}</small>
            </td>
            <td>
              <textarea
                v-model="row.memo"
                class="memo-textarea"
                maxlength="500"
                wrap="soft"
                :class="{ 'cell-error': !!rowErrors[row.localKey]?.memo }"
                :disabled="isFieldDisabled(row, 'memo')"
                @focus="activeType = row.type"
                @input="handleFieldInput(row, 'memo')"
              />
              <small v-if="rowErrors[row.localKey]?.memo" class="field-error">{{
                rowErrors[row.localKey]?.memo
              }}</small>
            </td>
            <td class="table-actions-cell">
              <small v-if="rowErrors[row.localKey]?.id" class="field-error">{{
                rowErrors[row.localKey]?.id
              }}</small>
              <button
                type="button"
                :disabled="loading || saving || !isRowDirty(row)"
                @click="updateRow(row)"
              >
                {{ saving ? '更新中...' : '更新' }}
              </button>
              <button
                type="button"
                class="secondary-button"
                :disabled="loading || saving"
                @click="copyRow(row)"
              >
                コピー
              </button>
              <button
                type="button"
                class="danger-button"
                :disabled="loading || saving"
                @click="deleteRow(row)"
              >
                削除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="transaction-mobile">
      <button
        type="button"
        class="entry-add-button"
        :disabled="loading || saving"
        @click="openCreateSheet"
      >
        ＋ 追加
      </button>
      <p v-if="mobileEntries.length === 0" class="empty-cell">
        この月の家計簿データはまだありません。
      </p>
      <ul v-else class="entry-list">
        <li v-for="row in mobileEntries" :key="`entry-${row.localKey}`">
          <button
            type="button"
            class="entry-item"
            :class="[
              `type-${row.type.toLowerCase()}`,
              { 'entry-item-error': !!rowErrors[row.localKey] },
            ]"
            @click="openEditSheet(row)"
          >
            <span class="entry-line"
              ><span class="entry-head"
                ><span class="entry-badge">{{ typeLabel(row.type) }}</span
                ><span class="entry-date">{{ row.date }}</span></span
              ><span class="entry-amount">{{ formatCurrency(Number(row.amount) || 0) }}</span></span
            >
            <span class="entry-detail">{{ rowCategoryName(row) }} → {{ rowPaymentName(row) }}</span>
            <span v-if="row.memo" class="entry-memo">{{ row.memo }}</span>
          </button>
        </li>
      </ul>
    </div>
  </section>

  <div v-if="sheetOpen" class="sheet-overlay" @click.self="closeSheet">
    <div class="sheet-panel" role="dialog" aria-modal="true">
      <div class="sheet-header">
        <h2>{{ sheetMode === 'create' ? '家計簿を追加' : '家計簿を編集' }}</h2>
        <button type="button" class="sheet-close" aria-label="閉じる" @click="closeSheet">×</button>
      </div>
      <div class="sheet-body">
        <label class="card-field"
          ><span>日付</span
          ><input
            v-model="draft.date"
            type="date"
            required
            :min="monthStartDate"
            :max="monthEndDate"
            :class="{ 'cell-error': !!draftErrors.date }"
            @input="clearDraftError('date')"
          /><small v-if="draftErrors.date" class="field-error">{{ draftErrors.date }}</small></label
        >
        <label class="card-field"
          ><span>種別</span
          ><select
            v-model="draft.type"
            :class="{ 'cell-error': !!draftErrors.type }"
            @change="changeDraftType"
          >
            <option value="EXPENSE">支出</option>
            <option value="INCOME">収入</option>
            <option value="TRANSFER">振替</option></select
          ><small v-if="draftErrors.type" class="field-error">{{ draftErrors.type }}</small></label
        >
        <label class="card-field"
          ><span>{{ draft.type === 'TRANSFER' ? '振替元' : 'カテゴリ' }}</span
          ><select
            v-model="draft.categoryId"
            :class="{ 'cell-error': !!draftErrors.categoryId }"
            @change="clearDraftError('categoryId')"
          >
            <option v-for="option in categoryOptions(draft)" :key="option.id" :value="option.id">
              {{ option.name }}
            </option></select
          ><small v-if="draftErrors.categoryId" class="field-error">{{
            draftErrors.categoryId
          }}</small></label
        >
        <label class="card-field"
          ><span>{{ draft.type === 'TRANSFER' ? '振替先' : '支払い方法' }}</span
          ><select
            v-model="draft.paymentMethodId"
            :class="{ 'cell-error': !!draftErrors.paymentMethodId }"
            @change="clearDraftError('paymentMethodId')"
          >
            <option
              v-for="option in paymentMethodOptions(draft)"
              :key="option.id"
              :value="option.id"
            >
              {{ option.name }}
            </option></select
          ><small v-if="draftErrors.paymentMethodId" class="field-error">{{
            draftErrors.paymentMethodId
          }}</small></label
        >
        <label class="card-field"
          ><span>金額</span
          ><input
            v-model.number="draft.amount"
            type="number"
            min="1"
            inputmode="numeric"
            :class="{ 'cell-error': !!draftErrors.amount }"
            @input="clearDraftError('amount')"
          /><small v-if="draftErrors.amount" class="field-error">{{
            draftErrors.amount
          }}</small></label
        >
        <label class="card-field"
          ><span>メモ</span
          ><textarea
            v-model="draft.memo"
            class="memo-textarea"
            maxlength="500"
            wrap="soft"
            :class="{ 'cell-error': !!draftErrors.memo }"
            @input="clearDraftError('memo')"
          /><small v-if="draftErrors.memo" class="field-error">{{ draftErrors.memo }}</small></label
        >
      </div>
      <div class="sheet-actions">
        <button
          v-if="sheetMode === 'edit'"
          type="button"
          class="danger-button"
          :disabled="saving"
          @click="deleteFromSheet"
        >
          削除</button
        ><span class="sheet-actions-spacer"></span
        ><button type="button" class="secondary-button" :disabled="saving" @click="closeSheet">
          キャンセル</button
        ><button type="button" :disabled="saving" @click="submitSheet">
          {{ sheetMode === 'create' ? '登録' : '更新' }}
        </button>
      </div>
    </div>
  </div>
</template>
