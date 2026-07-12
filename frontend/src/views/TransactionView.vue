<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, toMessage } from '@/api/http'
import {
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
  type TransactionType,
  type TransferAccount,
} from '@/api/kakeibo'
import { useCellNavigation } from '@/composables/useCellNavigation'
import { compareByDisplayOrder, compareCategories } from '@/masters'
import { formatDate, pad2 } from '@/transactions/dates'
import {
  buildSaveEntries,
  createCopiedRow,
  createEmptyRow,
  isBlankNewRow,
  isTransactionField,
  isUnsavedEnteredRow,
  snapshotOf,
  toRequest,
  toRow,
  type EditableField,
  type RowDefaults,
  type SaveEntry,
  type TransactionField,
  type TransactionFieldErrors,
  type TransactionRow,
} from '@/transactions/rowModel'
import { validateEntries, type ValidationContext } from '@/transactions/validation'

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
const loadError = ref<string | null>(null)
const saveError = ref<string | null>(null)
const rowErrors = reactive<Record<string, TransactionFieldErrors>>({})
const savedSnapshot = ref('[]')

// スマホ用の入力/編集シート。draft を編集し、保存時に rows へ反映してから自動保存する。
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

const {
  activeRow,
  startCellEdit,
  isActiveCell,
  handleCellKeydown,
  focusCell,
  focusFirstError,
  createFocusRestoreTarget,
  restoreFocus,
} = useCellNavigation({
  rows,
  isFieldDisabled,
  rowDefaults,
  onRowRestored: (row) => {
    clearRowError(row)
    scheduleAutoSave()
  },
})

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
// スマホの明細一覧は新しい順で読みやすくする(配列自体の並びは変えない)。
const mobileEntries = computed(() =>
  [...enteredRows.value].sort((left, right) => {
    if (left.date !== right.date) {
      return left.date < right.date ? 1 : -1
    }
    return left.localKey < right.localKey ? 1 : -1
  }),
)
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

    categories.value = [...categoryItems].sort(compareCategories)
    paymentMethods.value = [...paymentMethodItems].sort(compareByDisplayOrder)
    transferAccounts.value = [...transferAccountItems].sort(compareByDisplayOrder)
    rows.value = transactionItems.map(toRow)
    sortRowsByDate()
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

  // 保存の直前に日付順へ整える(入力停止後の自然な区切りで並び替える)。
  sortRowsByDate()
  const entries = buildSaveEntries(rows.value)
  saveError.value = null
  clearRowErrors()

  const validationErrors = validateEntries(entries, validationContext())
  if (Object.keys(validationErrors).length > 0) {
    Object.assign(rowErrors, validationErrors)
    saveError.value = '入力内容に誤りがあります'
    focusFirstError(entries, rowErrors)
    return
  }

  const includesNewRows = entries.some((entry) => entry.row.id === null)
  const focusRestoreTarget = createFocusRestoreTarget(entries)
  saving.value = true

  try {
    const savedTransactions = await saveMonthlyTransactions(
      period.year,
      period.month,
      entries.map((entry) => entry.request),
    )
    applySavedIds(entries, savedTransactions)

    persistedSummary.value = await getMonthlySummary(period.year, period.month)
    savedSnapshot.value = JSON.stringify(
      entries.map((entry) => ({ ...entry.request, id: entry.row.id })),
    )
    ensureTrailingBlankRow()

    if (includesNewRows) {
      restoreFocus(focusRestoreTarget)
    }
  } catch (error) {
    if (error instanceof ApiError) {
      applyApiErrors(error, entries)
      focusFirstError(entries, rowErrors)
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

/** 保存APIはリクエストと同じ並びで返すため、位置で新規行のIDを引き当てられる。 */
function applySavedIds(entries: SaveEntry[], savedTransactions: Transaction[]): void {
  entries.forEach((entry, index) => {
    const saved = savedTransactions[index]
    if (saved) {
      entry.row.id = saved.id
    }
  })
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

function scheduleAutoSave(delay = 700): void {
  if (autoSaveTimer !== undefined) {
    window.clearTimeout(autoSaveTimer)
  }

  autoSaveTimer = window.setTimeout(() => {
    autoSaveTimer = undefined
    void autoSaveMonth()
  }, delay)
}

function rowDefaults(): RowDefaults {
  return {
    date: defaultNewRowDate(),
    categoryId: defaultCategoryId('EXPENSE'),
    paymentMethodId: defaultPaymentMethodId(),
  }
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

// 種別に合わせてカテゴリ/支払い方法の既定値を入れ直す。グリッドとシート双方から使う。
function applyTypeDefaults(row: TransactionRow): void {
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
}

function handleTypeChange(row: TransactionRow): void {
  clearFieldError(row, 'type')
  applyTypeDefaults(row)
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

function handleCellInput(row: TransactionRow, field: TransactionField): void {
  clearFieldError(row, field)
  ensureTrailingBlankRow()
  scheduleAutoSave()
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

function currentSnapshot(): string {
  return snapshotOf(buildSaveEntries(rows.value))
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

function clearRowErrors(): void {
  Object.keys(rowErrors).forEach((key) => {
    delete rowErrors[key]
  })
}

function clearRowError(row: TransactionRow): void {
  delete rowErrors[row.localKey]
}

// PC表示(テーブル)は日付の古い順(上→下)。空の新規行は末尾に残し、同日は元の順序を保つ(安定ソート)。
// 表示・Tab移動・保存順を一致させるため配列そのものを並べ替える。入力中は動かさず、読込時と保存時にだけ適用する。
function sortRowsByDate(): void {
  rows.value = [...rows.value].sort((left, right) => {
    const leftBlank = isBlankNewRow(left)
    const rightBlank = isBlankNewRow(right)
    if (leftBlank !== rightBlank) {
      return leftBlank ? 1 : -1
    }
    if (leftBlank || left.date === right.date) {
      return 0
    }
    return left.date < right.date ? -1 : 1
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
    rows.value = [...rows.value, createEmptyRow(rowDefaults())]
  }
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
  sheetOpen.value = true
}

function closeSheet(): void {
  sheetOpen.value = false
}

function changeDraftType(): void {
  clearDraftError('type')
  applyTypeDefaults(draft)
}

function submitSheet(): void {
  clearDraftErrors()
  const request = toRequest(draft, 0)
  const found = validateEntries([{ row: draft, request }], validationContext())[draft.localKey]
  if (found) {
    Object.assign(draftErrors, found)
    return
  }

  if (sheetMode.value === 'edit') {
    const target = rows.value.find((row) => row.localKey === sheetRowKey.value)
    if (target) {
      applyDraftTo(target)
    }
  } else {
    const created = createEmptyRow(rowDefaults())
    applyDraftTo(created)
    insertBeforeBlank(created)
  }

  ensureTrailingBlankRow()
  closeSheet()
  scheduleAutoSave(0)
}

function applyDraftTo(row: TransactionRow): void {
  row.date = draft.date
  row.type = draft.type
  row.categoryId = draft.categoryId
  row.paymentMethodId = draft.paymentMethodId
  row.amount = draft.amount
  row.memo = draft.memo
}

// 追加した行は末尾の空行の前に差し込む(空行は自動保存の対象外)。
function insertBeforeBlank(newRow: TransactionRow): void {
  const blankIndex = rows.value.findIndex((row) => !row.deleted && isBlankNewRow(row))
  rows.value =
    blankIndex === -1
      ? [...rows.value, newRow]
      : [...rows.value.slice(0, blankIndex), newRow, ...rows.value.slice(blankIndex)]
}

function deleteFromSheet(): void {
  if (sheetMode.value === 'edit' && sheetRowKey.value) {
    const target = rows.value.find((row) => row.localKey === sheetRowKey.value)
    if (target) {
      deleteRow(target)
    }
  }
  closeSheet()
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
          <tr v-for="row in rows" :key="row.localKey" :class="{ 'deleted-row': row.deleted }">
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
              />
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
                <option v-for="option in categoryOptions(row)" :key="option.id" :value="option.id">
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
              />
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

    <!-- 狭幅(スマホ)用: 一覧は読み取り専用の明細、追加/編集はボトムシートのフォームで行う。 -->
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
            <span class="entry-line">
              <span class="entry-head">
                <span class="entry-badge">{{ typeLabel(row.type) }}</span>
                <span class="entry-date">{{ row.date }}</span>
              </span>
              <span class="entry-amount">{{ formatCurrency(Number(row.amount) || 0) }}</span>
            </span>
            <span class="entry-detail">{{ rowCategoryName(row) }} → {{ rowPaymentName(row) }}</span>
            <span v-if="row.memo" class="entry-memo">{{ row.memo }}</span>
          </button>
        </li>
      </ul>
    </div>
  </section>

  <!-- 追加/編集用のボトムシート -->
  <div v-if="sheetOpen" class="sheet-overlay" @click.self="closeSheet">
    <div class="sheet-panel" role="dialog" aria-modal="true">
      <div class="sheet-header">
        <h2>{{ sheetMode === 'create' ? '家計簿を追加' : '家計簿を編集' }}</h2>
        <button type="button" class="sheet-close" aria-label="閉じる" @click="closeSheet">×</button>
      </div>

      <div class="sheet-body">
        <label class="card-field">
          <span>日付</span>
          <input
            v-model="draft.date"
            type="date"
            required
            :min="monthStartDate"
            :max="monthEndDate"
            :class="{ 'cell-error': !!draftErrors.date }"
            @input="clearDraftError('date')"
          />
          <small v-if="draftErrors.date" class="field-error">{{ draftErrors.date }}</small>
        </label>

        <label class="card-field">
          <span>種別</span>
          <select
            v-model="draft.type"
            :class="{ 'cell-error': !!draftErrors.type }"
            @change="changeDraftType"
          >
            <option value="EXPENSE">支出</option>
            <option value="INCOME">収入</option>
            <option value="TRANSFER">振替</option>
          </select>
          <small v-if="draftErrors.type" class="field-error">{{ draftErrors.type }}</small>
        </label>

        <label class="card-field">
          <span>{{ draft.type === 'TRANSFER' ? '振替元' : 'カテゴリ' }}</span>
          <select
            v-model="draft.categoryId"
            :class="{ 'cell-error': !!draftErrors.categoryId }"
            @change="clearDraftError('categoryId')"
          >
            <option v-for="option in categoryOptions(draft)" :key="option.id" :value="option.id">
              {{ option.name }}
            </option>
          </select>
          <small v-if="draftErrors.categoryId" class="field-error">
            {{ draftErrors.categoryId }}
          </small>
        </label>

        <label class="card-field">
          <span>{{ draft.type === 'TRANSFER' ? '振替先' : '支払い方法' }}</span>
          <select
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
            </option>
          </select>
          <small v-if="draftErrors.paymentMethodId" class="field-error">
            {{ draftErrors.paymentMethodId }}
          </small>
        </label>

        <label class="card-field">
          <span>金額</span>
          <input
            v-model.number="draft.amount"
            type="number"
            min="1"
            inputmode="numeric"
            :class="{ 'cell-error': !!draftErrors.amount }"
            @input="clearDraftError('amount')"
          />
          <small v-if="draftErrors.amount" class="field-error">{{ draftErrors.amount }}</small>
        </label>

        <label class="card-field">
          <span>メモ</span>
          <textarea
            v-model="draft.memo"
            class="memo-textarea"
            maxlength="500"
            wrap="soft"
            :class="{ 'cell-error': !!draftErrors.memo }"
            @input="clearDraftError('memo')"
          />
          <small v-if="draftErrors.memo" class="field-error">{{ draftErrors.memo }}</small>
        </label>
      </div>

      <div class="sheet-actions">
        <button
          v-if="sheetMode === 'edit'"
          type="button"
          class="danger-button"
          @click="deleteFromSheet"
        >
          削除
        </button>
        <span class="sheet-actions-spacer"></span>
        <button type="button" class="secondary-button" @click="closeSheet">キャンセル</button>
        <button type="button" @click="submitSheet">保存</button>
      </div>
    </div>
  </div>
</template>
