<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, toMessage } from '@/api/http'
import {
  createRecurringTransactionTemplate,
  deleteRecurringTransactionTemplate,
  getCategories,
  getPaymentMethods,
  getRecurringTransactionCandidates,
  getRecurringTransactionTemplates,
  getTransferAccounts,
  registerRecurringTransactions,
  updateRecurringTransactionTemplate,
  type Category,
  type PaymentMethod,
  type RecurringTransactionCandidate,
  type RecurringTransactionRegistrationItem,
  type RecurringTransactionTemplate,
  type RecurringTransactionTemplateRequest,
  type TransactionType,
  type TransferAccount,
} from '@/api/kakeibo'
import { compareByDisplayOrder, compareCategories, nextDisplayOrderOf } from '@/masters'
import { pad2 } from '@/transactions/dates'

type OptionalNumber = number | ''

type TemplateForm = {
  name: string
  enabled: boolean
  dayOfMonth: OptionalNumber
  type: TransactionType
  categoryId: OptionalNumber
  paymentMethodId: OptionalNumber
  transferSourceId: OptionalNumber
  transferDestinationId: OptionalNumber
  defaultAmount: OptionalNumber
  memo: string
  displayOrder: OptionalNumber
}

type TemplateField = keyof TemplateForm
type TemplateErrors = Partial<Record<TemplateField, string>>

type CandidateDraft = Omit<
  RecurringTransactionCandidate,
  | 'categoryId'
  | 'paymentMethodId'
  | 'transferSourceId'
  | 'transferDestinationId'
  | 'amount'
  | 'memo'
> & {
  selected: boolean
  categoryId: OptionalNumber
  paymentMethodId: OptionalNumber
  transferSourceId: OptionalNumber
  transferDestinationId: OptionalNumber
  amount: OptionalNumber
  memo: string
}

type CandidateField =
  | 'date'
  | 'type'
  | 'categoryId'
  | 'paymentMethodId'
  | 'transferSourceId'
  | 'transferDestinationId'
  | 'amount'
  | 'memo'
type CandidateErrors = Partial<Record<CandidateField, string>>

const route = useRoute()
const router = useRouter()
const today = new Date()
const initialPeriod = parseMonthQuery(route.query.month)
const period = reactive({
  year: initialPeriod?.year ?? today.getFullYear(),
  month: initialPeriod?.month ?? today.getMonth() + 1,
})

const categories = ref<Category[]>([])
const paymentMethods = ref<PaymentMethod[]>([])
const transferAccounts = ref<TransferAccount[]>([])
const templates = ref<RecurringTransactionTemplate[]>([])
const editForms = reactive<Record<number, TemplateForm>>({})
const editErrors = reactive<Record<number, TemplateErrors>>({})
const rowErrors = reactive<Record<number, string>>({})
const createForm = reactive<TemplateForm>(emptyTemplateForm())
const createErrors = reactive<TemplateErrors>({})

const candidates = ref<CandidateDraft[] | null>(null)
const candidateErrors = reactive<Record<number, CandidateErrors>>({})
const loading = ref(true)
const confirming = ref(false)
const registering = ref(false)
const creating = ref(false)
const updatingIds = ref<Set<number>>(new Set())
const deletingIds = ref<Set<number>>(new Set())
const loadError = ref<string | null>(null)
const candidateError = ref<string | null>(null)
const templateError = ref<string | null>(null)
const announcement = ref('')

const monthInputValue = computed(() => `${period.year}-${pad2(period.month)}`)
const monthStartDate = computed(() => `${monthInputValue.value}-01`)
const monthEndDate = computed(() => {
  const lastDay = new Date(period.year, period.month, 0).getDate()
  return `${monthInputValue.value}-${pad2(lastDay)}`
})
const selectedCount = computed(
  () =>
    candidates.value?.filter((candidate) => candidate.selected && !candidate.registered).length ??
    0,
)

onMounted(() => {
  void loadPage()
})

async function loadPage(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    const [categoryItems, paymentMethodItems, transferAccountItems, templateItems] =
      await Promise.all([
        getCategories(),
        getPaymentMethods(),
        getTransferAccounts(),
        getRecurringTransactionTemplates(),
      ])
    categories.value = [...categoryItems].sort(compareCategories)
    paymentMethods.value = [...paymentMethodItems].sort(compareByDisplayOrder)
    transferAccounts.value = [...transferAccountItems].sort(compareByDisplayOrder)
    setTemplates(templateItems)
    resetCreateForm()
  } catch (error) {
    loadError.value = toMessage(error)
  } finally {
    loading.value = false
  }
}

function setTemplates(items: RecurringTransactionTemplate[]): void {
  templates.value = [...items].sort(compareTemplates)
  Object.keys(editForms).forEach((key) => delete editForms[Number(key)])
  Object.keys(editErrors).forEach((key) => delete editErrors[Number(key)])
  Object.keys(rowErrors).forEach((key) => delete rowErrors[Number(key)])
  templates.value.forEach((template) => {
    editForms[template.id] = toTemplateForm(template)
  })
}

function upsertTemplate(template: RecurringTransactionTemplate): void {
  templates.value = [
    ...templates.value.filter((current) => current.id !== template.id),
    template,
  ].sort(compareTemplates)
  editForms[template.id] = toTemplateForm(template)
  editErrors[template.id] = {}
  delete rowErrors[template.id]
}

function removeTemplate(id: number): void {
  templates.value = templates.value.filter((template) => template.id !== id)
  delete editForms[id]
  delete editErrors[id]
  delete rowErrors[id]
}

function compareTemplates(
  left: RecurringTransactionTemplate,
  right: RecurringTransactionTemplate,
): number {
  const displayOrder = left.displayOrder - right.displayOrder
  return displayOrder !== 0 ? displayOrder : left.id - right.id
}

function emptyTemplateForm(): TemplateForm {
  return {
    name: '',
    enabled: true,
    dayOfMonth: 1,
    type: 'EXPENSE',
    categoryId: '',
    paymentMethodId: '',
    transferSourceId: '',
    transferDestinationId: '',
    defaultAmount: '',
    memo: '',
    displayOrder: 0,
  }
}

function resetCreateForm(): void {
  Object.assign(createForm, emptyTemplateForm(), {
    categoryId: defaultCategoryId('EXPENSE'),
    paymentMethodId: defaultPaymentMethodId(),
    displayOrder: nextDisplayOrderOf(templates.value),
  })
  clearObject(createErrors)
}

function toTemplateForm(template: RecurringTransactionTemplate): TemplateForm {
  return {
    name: template.name,
    enabled: template.enabled,
    dayOfMonth: template.dayOfMonth,
    type: template.type,
    categoryId: template.categoryId ?? '',
    paymentMethodId: template.paymentMethodId ?? '',
    transferSourceId: template.transferSourceId ?? '',
    transferDestinationId: template.transferDestinationId ?? '',
    defaultAmount: template.defaultAmount ?? '',
    memo: template.memo ?? '',
    displayOrder: template.displayOrder,
  }
}

function toTemplateRequest(form: TemplateForm): RecurringTransactionTemplateRequest {
  return {
    name: form.name.trim(),
    enabled: form.enabled,
    dayOfMonth: Number(form.dayOfMonth),
    type: form.type,
    categoryId: form.type === 'TRANSFER' ? null : toNullableNumber(form.categoryId),
    paymentMethodId: form.type === 'EXPENSE' ? toNullableNumber(form.paymentMethodId) : null,
    transferSourceId: form.type === 'TRANSFER' ? toNullableNumber(form.transferSourceId) : null,
    transferDestinationId:
      form.type === 'TRANSFER' ? toNullableNumber(form.transferDestinationId) : null,
    defaultAmount: toNullableNumber(form.defaultAmount),
    memo: form.memo.trim() === '' ? null : form.memo.trim(),
    displayOrder: Number(form.displayOrder),
  }
}

async function submitCreate(): Promise<void> {
  clearObject(createErrors)
  templateError.value = null
  const errors = validateTemplate(createForm)
  if (Object.keys(errors).length > 0) {
    Object.assign(createErrors, errors)
    templateError.value = '入力内容に誤りがあります'
    return
  }
  if (creating.value) {
    return
  }

  creating.value = true
  try {
    const created = await createRecurringTransactionTemplate(toTemplateRequest(createForm))
    upsertTemplate(created)
    resetCreateForm()
    announcement.value = `定期取引テンプレート「${created.name}」を登録しました`
  } catch (error) {
    applyTemplateApiErrors(error, createErrors)
    templateError.value = toMessage(error)
  } finally {
    creating.value = false
  }
}

async function updateTemplate(template: RecurringTransactionTemplate): Promise<void> {
  const form = editForms[template.id]
  if (!form || updatingIds.value.has(template.id)) {
    return
  }
  editErrors[template.id] = {}
  delete rowErrors[template.id]
  const errors = validateTemplate(form)
  if (Object.keys(errors).length > 0) {
    editErrors[template.id] = errors
    rowErrors[template.id] = '入力内容に誤りがあります'
    return
  }

  addId(updatingIds, template.id)
  try {
    const updated = await updateRecurringTransactionTemplate(template.id, toTemplateRequest(form))
    upsertTemplate(updated)
    announcement.value = `定期取引テンプレート「${updated.name}」を更新しました`
  } catch (error) {
    applyTemplateApiErrors(error, editErrors[template.id])
    rowErrors[template.id] = toMessage(error)
  } finally {
    removeId(updatingIds, template.id)
  }
}

async function confirmDeleteTemplate(template: RecurringTransactionTemplate): Promise<void> {
  if (
    deletingIds.value.has(template.id) ||
    !window.confirm(`定期取引テンプレート「${template.name}」を削除しますか？`)
  ) {
    return
  }

  addId(deletingIds, template.id)
  delete rowErrors[template.id]
  try {
    await deleteRecurringTransactionTemplate(template.id)
    removeTemplate(template.id)
    resetCreateForm()
    candidates.value = null
    clearCandidateErrors()
    announcement.value = `定期取引テンプレート「${template.name}」を削除しました`
  } catch (error) {
    rowErrors[template.id] = toMessage(error)
  } finally {
    removeId(deletingIds, template.id)
  }
}

function validateTemplate(form: TemplateForm): TemplateErrors {
  const errors: TemplateErrors = {}
  if (form.name.trim() === '') {
    errors.name = 'テンプレート名を入力してください'
  } else if (form.name.trim().length > 100) {
    errors.name = 'テンプレート名は100文字以内で入力してください'
  }
  if (!isIntegerInRange(form.dayOfMonth, 1, 31)) {
    errors.dayOfMonth = '毎月の日は1から31の整数で入力してください'
  }
  if (!isIntegerInRange(form.displayOrder, 0)) {
    errors.displayOrder = '表示順は0以上の整数で入力してください'
  }
  if (form.defaultAmount !== '' && !isIntegerInRange(form.defaultAmount, 1)) {
    errors.defaultAmount = '標準金額は1以上の整数で入力してください'
  }
  if (form.memo.length > 500) {
    errors.memo = 'メモは500文字以内で入力してください'
  }
  if (form.type === 'TRANSFER') {
    if (!hasTransferAccount(form.transferSourceId)) {
      errors.transferSourceId = '振替元を選択してください'
    }
    if (!hasTransferAccount(form.transferDestinationId)) {
      errors.transferDestinationId = '振替先を選択してください'
    }
  } else {
    if (!hasCategory(form.categoryId, form.type)) {
      errors.categoryId = '種別に合うカテゴリを選択してください'
    }
    if (form.type === 'EXPENSE' && !hasPaymentMethod(form.paymentMethodId)) {
      errors.paymentMethodId = '支払い方法を選択してください'
    }
  }
  return errors
}

function changeTemplateType(form: TemplateForm): void {
  form.categoryId = ''
  form.paymentMethodId = ''
  form.transferSourceId = ''
  form.transferDestinationId = ''
  if (form.type === 'TRANSFER') {
    form.transferSourceId = defaultTransferAccountId()
    form.transferDestinationId = defaultTransferAccountId()
  } else {
    form.categoryId = defaultCategoryId(form.type)
    form.paymentMethodId = form.type === 'EXPENSE' ? defaultPaymentMethodId() : ''
  }
}

async function confirmCandidates(): Promise<void> {
  confirming.value = true
  candidateError.value = null
  announcement.value = ''
  clearCandidateErrors()
  try {
    const result = await getRecurringTransactionCandidates(period.year, period.month)
    candidates.value = result.items.map(toCandidateDraft)
    announcement.value = `${period.year}年${period.month}月の登録内容を表示しました`
  } catch (error) {
    candidates.value = null
    candidateError.value = toMessage(error)
  } finally {
    confirming.value = false
  }
}

function toCandidateDraft(candidate: RecurringTransactionCandidate): CandidateDraft {
  return {
    ...candidate,
    selected: !candidate.registered,
    categoryId: candidate.categoryId ?? '',
    paymentMethodId: candidate.paymentMethodId ?? '',
    transferSourceId: candidate.transferSourceId ?? '',
    transferDestinationId: candidate.transferDestinationId ?? '',
    amount: candidate.amount ?? '',
    memo: candidate.memo ?? '',
  }
}

function changeCandidateType(candidate: CandidateDraft): void {
  candidate.categoryId = ''
  candidate.paymentMethodId = ''
  candidate.transferSourceId = ''
  candidate.transferDestinationId = ''
  if (candidate.type === 'TRANSFER') {
    candidate.transferSourceId = defaultTransferAccountId()
    candidate.transferDestinationId = defaultTransferAccountId()
  } else {
    candidate.categoryId = defaultCategoryId(candidate.type)
    candidate.paymentMethodId = candidate.type === 'EXPENSE' ? defaultPaymentMethodId() : ''
  }
  delete candidateErrors[candidate.templateId]
}

async function submitCandidates(): Promise<void> {
  candidateError.value = null
  clearCandidateErrors()
  const selected = candidates.value?.filter(
    (candidate) => candidate.selected && !candidate.registered,
  )
  if (!selected || selected.length === 0 || registering.value) {
    return
  }

  selected.forEach((candidate) => {
    const errors = validateCandidate(candidate)
    if (Object.keys(errors).length > 0) {
      candidateErrors[candidate.templateId] = errors
    }
  })
  if (Object.keys(candidateErrors).length > 0) {
    candidateError.value = '入力内容に誤りがあります'
    return
  }

  registering.value = true
  try {
    const result = await registerRecurringTransactions({
      year: period.year,
      month: period.month,
      items: selected.map(toRegistrationItem),
    })
    await confirmCandidates()
    const skipped = result.skippedTemplateIds.length
    announcement.value =
      skipped === 0
        ? `${result.created.length}件の定期取引を登録しました`
        : `${result.created.length}件を登録しました。${skipped}件は登録済みのためスキップしました`
  } catch (error) {
    candidateError.value = toMessage(error)
  } finally {
    registering.value = false
  }
}

function validateCandidate(candidate: CandidateDraft): CandidateErrors {
  const errors: CandidateErrors = {}
  if (!isDateInSelectedMonth(candidate.date)) {
    errors.date = '対象月の日付を入力してください'
  }
  if (!isIntegerInRange(candidate.amount, 1)) {
    errors.amount = '金額を1以上の整数で入力してください'
  }
  if (candidate.memo.length > 500) {
    errors.memo = 'メモは500文字以内で入力してください'
  }
  if (candidate.type === 'TRANSFER') {
    if (!hasTransferAccount(candidate.transferSourceId)) {
      errors.transferSourceId = '振替元を選択してください'
    }
    if (!hasTransferAccount(candidate.transferDestinationId)) {
      errors.transferDestinationId = '振替先を選択してください'
    }
  } else {
    if (!hasCategory(candidate.categoryId, candidate.type)) {
      errors.categoryId = '種別に合うカテゴリを選択してください'
    }
    if (candidate.type === 'EXPENSE' && !hasPaymentMethod(candidate.paymentMethodId)) {
      errors.paymentMethodId = '支払い方法を選択してください'
    }
  }
  return errors
}

function toRegistrationItem(candidate: CandidateDraft): RecurringTransactionRegistrationItem {
  return {
    templateId: candidate.templateId,
    date: candidate.date,
    type: candidate.type,
    categoryId: candidate.type === 'TRANSFER' ? null : toNullableNumber(candidate.categoryId),
    paymentMethodId:
      candidate.type === 'EXPENSE' ? toNullableNumber(candidate.paymentMethodId) : null,
    transferSourceId:
      candidate.type === 'TRANSFER' ? toNullableNumber(candidate.transferSourceId) : null,
    transferDestinationId:
      candidate.type === 'TRANSFER' ? toNullableNumber(candidate.transferDestinationId) : null,
    amount: Number(candidate.amount),
    memo: candidate.memo.trim() === '' ? null : candidate.memo.trim(),
  }
}

function handleMonthInput(event: Event): void {
  const input = event.target as HTMLInputElement
  const parsed = parseMonthValue(input.value)
  if (!parsed) {
    input.value = monthInputValue.value
    return
  }
  period.year = parsed.year
  period.month = parsed.month
  candidates.value = null
  candidateError.value = null
  announcement.value = ''
  clearCandidateErrors()
  void router.replace({ query: { ...route.query, month: monthInputValue.value } })
}

function categoriesForType(type: TransactionType): Category[] {
  return type === 'TRANSFER' ? [] : categories.value.filter((category) => category.type === type)
}

function defaultCategoryId(type: TransactionType): OptionalNumber {
  return categoriesForType(type)[0]?.id ?? ''
}

function defaultPaymentMethodId(): OptionalNumber {
  return paymentMethods.value[0]?.id ?? ''
}

function defaultTransferAccountId(): OptionalNumber {
  return transferAccounts.value[0]?.id ?? ''
}

function hasCategory(value: OptionalNumber, type: TransactionType): boolean {
  return (
    value !== '' &&
    categories.value.some((category) => category.id === value && category.type === type)
  )
}

function hasPaymentMethod(value: OptionalNumber): boolean {
  return value !== '' && paymentMethods.value.some((paymentMethod) => paymentMethod.id === value)
}

function hasTransferAccount(value: OptionalNumber): boolean {
  return value !== '' && transferAccounts.value.some((account) => account.id === value)
}

function primaryLabel(type: TransactionType): string {
  return type === 'TRANSFER' ? '振替元' : 'カテゴリ'
}

function secondaryLabel(type: TransactionType): string {
  return type === 'TRANSFER' ? '振替先' : '支払い方法'
}

function isDateInSelectedMonth(value: string): boolean {
  return (
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    value >= monthStartDate.value &&
    value <= monthEndDate.value
  )
}

function isIntegerInRange(value: OptionalNumber, min: number, max?: number): boolean {
  const number = Number(value)
  return (
    value !== '' &&
    Number.isInteger(number) &&
    number >= min &&
    (max === undefined || number <= max)
  )
}

function toNullableNumber(value: OptionalNumber): number | null {
  return value === '' ? null : Number(value)
}

function parseMonthQuery(value: unknown): { year: number; month: number } | null {
  return parseMonthValue(Array.isArray(value) ? value[0] : typeof value === 'string' ? value : '')
}

function parseMonthValue(value: string): { year: number; month: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(value)
  if (!match) {
    return null
  }
  const year = Number(match[1])
  const month = Number(match[2])
  return year >= 1 && month >= 1 && month <= 12 ? { year, month } : null
}

function applyTemplateApiErrors(error: unknown, errors: TemplateErrors): void {
  if (!(error instanceof ApiError)) {
    return
  }
  error.errors.forEach((fieldError) => {
    if (fieldError.field in createForm) {
      errors[fieldError.field as TemplateField] = fieldError.message
    }
  })
}

function clearCandidateErrors(): void {
  Object.keys(candidateErrors).forEach((key) => delete candidateErrors[Number(key)])
}

function clearObject<T extends object>(target: T): void {
  Object.keys(target).forEach((key) => delete target[key as keyof T])
}

function addId(target: typeof updatingIds, id: number): void {
  target.value = new Set([...target.value, id])
}

function removeId(target: typeof updatingIds, id: number): void {
  const next = new Set(target.value)
  next.delete(id)
  target.value = next
}
</script>

<template>
  <section class="page-heading">
    <h1>定期取引</h1>
    <p>給与、家賃、光熱費などのテンプレートから、確認した内容だけを今月分として登録できます。</p>
  </section>

  <p v-if="announcement" class="message success" aria-live="polite">{{ announcement }}</p>
  <p v-if="loadError" class="message error">{{ loadError }}</p>

  <section class="status-card" aria-labelledby="recurring-register-heading">
    <div>
      <p class="eyebrow">今月分を登録</p>
      <h2 id="recurring-register-heading">登録内容を確認</h2>
    </div>

    <div class="month-toolbar recurring-month-toolbar">
      <label class="month-picker">
        <span>対象月</span>
        <input
          type="month"
          required
          :value="monthInputValue"
          :disabled="loading || confirming || registering"
          @change="handleMonthInput"
        />
      </label>
      <button
        type="button"
        :disabled="loading || confirming || registering"
        @click="confirmCandidates"
      >
        {{ confirming ? '確認中...' : '登録内容を確認' }}
      </button>
      <RouterLink
        class="text-link recurring-home-link"
        :to="{ name: 'home', query: { month: monthInputValue } }"
        >家計簿入力で確認</RouterLink
      >
    </div>

    <p class="summary-note">
      対象月または「登録内容を確認」を操作すると、確認表の編集中の内容は破棄されます。
    </p>
    <p v-if="candidateError" class="message error">{{ candidateError }}</p>

    <template v-if="candidates">
      <div class="table-wrap">
        <table class="category-table recurring-candidates-table">
          <thead>
            <tr>
              <th scope="col">選択</th>
              <th scope="col">テンプレート</th>
              <th scope="col">日付</th>
              <th scope="col">種別</th>
              <th scope="col">選択項目1</th>
              <th scope="col">選択項目2</th>
              <th scope="col">金額</th>
              <th scope="col">メモ</th>
              <th scope="col">状態</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="candidates.length === 0">
              <td colspan="9" class="empty-cell">有効な定期取引テンプレートはありません。</td>
            </tr>
            <tr
              v-for="candidate in candidates"
              :key="candidate.templateId"
              :class="{ 'registered-candidate-row': candidate.registered }"
            >
              <td>
                <input
                  v-model="candidate.selected"
                  type="checkbox"
                  :aria-label="`${candidate.templateName}を登録`"
                  :disabled="candidate.registered || registering"
                />
              </td>
              <th scope="row">{{ candidate.templateName }}</th>
              <td>
                <input
                  v-model="candidate.date"
                  type="date"
                  :aria-label="`${candidate.templateName}の日付`"
                  :min="monthStartDate"
                  :max="monthEndDate"
                  :disabled="candidate.registered || registering"
                  :class="{ 'cell-error': candidateErrors[candidate.templateId]?.date }"
                />
                <small v-if="candidateErrors[candidate.templateId]?.date" class="field-error">
                  {{ candidateErrors[candidate.templateId]?.date }}
                </small>
              </td>
              <td>
                <select
                  v-model="candidate.type"
                  :aria-label="`${candidate.templateName}の種別`"
                  :disabled="candidate.registered || registering"
                  @change="changeCandidateType(candidate)"
                >
                  <option value="EXPENSE">支出</option>
                  <option value="INCOME">収入</option>
                  <option value="TRANSFER">振替</option>
                </select>
              </td>
              <td>
                <span class="recurring-cell-label">{{ primaryLabel(candidate.type) }}</span>
                <select
                  v-if="candidate.type === 'TRANSFER'"
                  v-model="candidate.transferSourceId"
                  :aria-label="`${candidate.templateName}の振替元`"
                  :disabled="candidate.registered || registering"
                  :class="{ 'cell-error': candidateErrors[candidate.templateId]?.transferSourceId }"
                >
                  <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
                    {{ account.name }}
                  </option>
                </select>
                <select
                  v-else
                  v-model="candidate.categoryId"
                  :aria-label="`${candidate.templateName}のカテゴリ`"
                  :disabled="candidate.registered || registering"
                  :class="{ 'cell-error': candidateErrors[candidate.templateId]?.categoryId }"
                >
                  <option
                    v-for="category in categoriesForType(candidate.type)"
                    :key="category.id"
                    :value="category.id"
                  >
                    {{ category.name }}
                  </option>
                </select>
                <small
                  v-if="
                    candidateErrors[candidate.templateId]?.transferSourceId ||
                    candidateErrors[candidate.templateId]?.categoryId
                  "
                  class="field-error"
                >
                  {{
                    candidateErrors[candidate.templateId]?.transferSourceId ??
                    candidateErrors[candidate.templateId]?.categoryId
                  }}
                </small>
              </td>
              <td>
                <span class="recurring-cell-label">{{ secondaryLabel(candidate.type) }}</span>
                <span v-if="candidate.type === 'INCOME'" class="not-applicable">対象なし</span>
                <select
                  v-else-if="candidate.type === 'TRANSFER'"
                  v-model="candidate.transferDestinationId"
                  :aria-label="`${candidate.templateName}の振替先`"
                  :disabled="candidate.registered || registering"
                  :class="{
                    'cell-error': candidateErrors[candidate.templateId]?.transferDestinationId,
                  }"
                >
                  <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
                    {{ account.name }}
                  </option>
                </select>
                <select
                  v-else
                  v-model="candidate.paymentMethodId"
                  :aria-label="`${candidate.templateName}の支払い方法`"
                  :disabled="candidate.registered || registering"
                  :class="{ 'cell-error': candidateErrors[candidate.templateId]?.paymentMethodId }"
                >
                  <option v-for="method in paymentMethods" :key="method.id" :value="method.id">
                    {{ method.name }}
                  </option>
                </select>
                <small
                  v-if="
                    candidateErrors[candidate.templateId]?.transferDestinationId ||
                    candidateErrors[candidate.templateId]?.paymentMethodId
                  "
                  class="field-error"
                >
                  {{
                    candidateErrors[candidate.templateId]?.transferDestinationId ??
                    candidateErrors[candidate.templateId]?.paymentMethodId
                  }}
                </small>
              </td>
              <td>
                <input
                  v-model.number="candidate.amount"
                  type="number"
                  min="1"
                  inputmode="numeric"
                  :aria-label="`${candidate.templateName}の金額`"
                  :disabled="candidate.registered || registering"
                  :class="{ 'cell-error': candidateErrors[candidate.templateId]?.amount }"
                />
                <small v-if="candidateErrors[candidate.templateId]?.amount" class="field-error">
                  {{ candidateErrors[candidate.templateId]?.amount }}
                </small>
              </td>
              <td>
                <textarea
                  v-model="candidate.memo"
                  maxlength="500"
                  wrap="soft"
                  :aria-label="`${candidate.templateName}のメモ`"
                  :disabled="candidate.registered || registering"
                  :class="{ 'cell-error': candidateErrors[candidate.templateId]?.memo }"
                ></textarea>
                <small v-if="candidateErrors[candidate.templateId]?.memo" class="field-error">
                  {{ candidateErrors[candidate.templateId]?.memo }}
                </small>
              </td>
              <td>
                <span v-if="candidate.registered" class="registered-badge">登録済み</span>
                <span v-else>未登録</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="recurring-register-actions">
        <span class="count-badge">選択中 {{ selectedCount }}件</span>
        <button
          type="button"
          :disabled="selectedCount === 0 || registering || confirming"
          @click="submitCandidates"
        >
          {{ registering ? '登録中...' : `選択した${selectedCount}件を登録` }}
        </button>
      </div>
    </template>
  </section>

  <section class="category-section" aria-labelledby="recurring-create-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">テンプレート管理</p>
        <h2 id="recurring-create-heading">テンプレートを追加</h2>
      </div>
    </div>

    <form class="recurring-template-form" @submit.prevent="submitCreate">
      <label class="field">
        <span>テンプレート名</span>
        <input v-model="createForm.name" type="text" maxlength="100" autocomplete="off" />
        <small v-if="createErrors.name" class="field-error">{{ createErrors.name }}</small>
      </label>
      <label class="checkbox-field recurring-enabled-field">
        <input v-model="createForm.enabled" type="checkbox" />
        <span>有効</span>
      </label>
      <label class="field">
        <span>毎月の日</span>
        <input
          v-model.number="createForm.dayOfMonth"
          type="number"
          min="1"
          max="31"
          inputmode="numeric"
        />
        <small v-if="createErrors.dayOfMonth" class="field-error">{{
          createErrors.dayOfMonth
        }}</small>
      </label>
      <label class="field">
        <span>種別</span>
        <select v-model="createForm.type" @change="changeTemplateType(createForm)">
          <option value="EXPENSE">支出</option>
          <option value="INCOME">収入</option>
          <option value="TRANSFER">振替</option>
        </select>
      </label>
      <label v-if="createForm.type === 'TRANSFER'" class="field">
        <span>振替元</span>
        <select v-model="createForm.transferSourceId">
          <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
            {{ account.name }}
          </option>
        </select>
        <small v-if="createErrors.transferSourceId" class="field-error">{{
          createErrors.transferSourceId
        }}</small>
      </label>
      <label v-else class="field">
        <span>カテゴリ</span>
        <select v-model="createForm.categoryId">
          <option
            v-for="category in categoriesForType(createForm.type)"
            :key="category.id"
            :value="category.id"
          >
            {{ category.name }}
          </option>
        </select>
        <small v-if="createErrors.categoryId" class="field-error">{{
          createErrors.categoryId
        }}</small>
      </label>
      <label v-if="createForm.type === 'TRANSFER'" class="field">
        <span>振替先</span>
        <select v-model="createForm.transferDestinationId">
          <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
            {{ account.name }}
          </option>
        </select>
        <small v-if="createErrors.transferDestinationId" class="field-error">{{
          createErrors.transferDestinationId
        }}</small>
      </label>
      <label v-else-if="createForm.type === 'EXPENSE'" class="field">
        <span>支払い方法</span>
        <select v-model="createForm.paymentMethodId">
          <option v-for="method in paymentMethods" :key="method.id" :value="method.id">
            {{ method.name }}
          </option>
        </select>
        <small v-if="createErrors.paymentMethodId" class="field-error">{{
          createErrors.paymentMethodId
        }}</small>
      </label>
      <div v-else class="field not-applicable-field">
        <span>支払い方法</span>
        <span class="not-applicable">対象なし</span>
      </div>
      <label class="field">
        <span>標準金額</span>
        <input
          v-model.number="createForm.defaultAmount"
          type="number"
          min="1"
          inputmode="numeric"
          aria-label="標準金額"
        />
        <small>金額が毎月変わる場合は空欄にできます。</small>
        <small v-if="createErrors.defaultAmount" class="field-error">{{
          createErrors.defaultAmount
        }}</small>
      </label>
      <label class="field recurring-memo-field">
        <span>メモ</span>
        <textarea v-model="createForm.memo" maxlength="500" wrap="soft"></textarea>
        <small v-if="createErrors.memo" class="field-error">{{ createErrors.memo }}</small>
      </label>
      <label class="field">
        <span>表示順</span>
        <input v-model.number="createForm.displayOrder" type="number" min="0" inputmode="numeric" />
        <small v-if="createErrors.displayOrder" class="field-error">{{
          createErrors.displayOrder
        }}</small>
      </label>
      <div class="form-actions">
        <button type="submit" :disabled="loading || creating">
          {{ creating ? '登録中...' : '登録' }}
        </button>
      </div>
    </form>
    <p class="summary-note">29日から31日は、その日がない月では月末日に丸められます。</p>
    <p v-if="templateError" class="message error">{{ templateError }}</p>
  </section>

  <section class="category-section" aria-labelledby="recurring-template-list-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">テンプレート管理</p>
        <h2 id="recurring-template-list-heading">テンプレート一覧</h2>
      </div>
      <span class="count-badge">{{ templates.length }}件</span>
    </div>

    <div class="table-wrap">
      <table class="category-table recurring-template-table">
        <thead>
          <tr>
            <th scope="col">テンプレート名</th>
            <th scope="col">有効</th>
            <th scope="col">毎月の日</th>
            <th scope="col">種別</th>
            <th scope="col">選択項目1</th>
            <th scope="col">選択項目2</th>
            <th scope="col">標準金額</th>
            <th scope="col">メモ</th>
            <th scope="col">表示順</th>
            <th scope="col" class="action-cell">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="templates.length === 0">
            <td colspan="10" class="empty-cell">テンプレートはまだありません。</td>
          </tr>
          <tr v-for="template in templates" :key="template.id">
            <template v-if="editForms[template.id]">
              <td>
                <span class="sr-only">{{ template.name }}</span>
                <input
                  v-model="editForms[template.id].name"
                  type="text"
                  maxlength="100"
                  autocomplete="off"
                  :aria-label="`${template.name}のテンプレート名`"
                />
                <small v-if="editErrors[template.id]?.name" class="field-error">{{
                  editErrors[template.id]?.name
                }}</small>
              </td>
              <td>
                <input
                  v-model="editForms[template.id].enabled"
                  type="checkbox"
                  :aria-label="`${template.name}を有効にする`"
                />
              </td>
              <td>
                <input
                  v-model.number="editForms[template.id].dayOfMonth"
                  type="number"
                  min="1"
                  max="31"
                  inputmode="numeric"
                  :aria-label="`${template.name}の毎月の日`"
                />
                <small v-if="editErrors[template.id]?.dayOfMonth" class="field-error">{{
                  editErrors[template.id]?.dayOfMonth
                }}</small>
              </td>
              <td>
                <select
                  v-model="editForms[template.id].type"
                  :aria-label="`${template.name}の種別`"
                  @change="changeTemplateType(editForms[template.id])"
                >
                  <option value="EXPENSE">支出</option>
                  <option value="INCOME">収入</option>
                  <option value="TRANSFER">振替</option>
                </select>
              </td>
              <td>
                <span class="recurring-cell-label">{{
                  primaryLabel(editForms[template.id].type)
                }}</span>
                <select
                  v-if="editForms[template.id].type === 'TRANSFER'"
                  v-model="editForms[template.id].transferSourceId"
                  :aria-label="`${template.name}の振替元`"
                >
                  <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
                    {{ account.name }}
                  </option>
                </select>
                <select
                  v-else
                  v-model="editForms[template.id].categoryId"
                  :aria-label="`${template.name}のカテゴリ`"
                >
                  <option
                    v-for="category in categoriesForType(editForms[template.id].type)"
                    :key="category.id"
                    :value="category.id"
                  >
                    {{ category.name }}
                  </option>
                </select>
                <small
                  v-if="
                    editErrors[template.id]?.transferSourceId || editErrors[template.id]?.categoryId
                  "
                  class="field-error"
                  >{{
                    editErrors[template.id]?.transferSourceId ?? editErrors[template.id]?.categoryId
                  }}</small
                >
              </td>
              <td>
                <span class="recurring-cell-label">{{
                  secondaryLabel(editForms[template.id].type)
                }}</span>
                <span v-if="editForms[template.id].type === 'INCOME'" class="not-applicable"
                  >対象なし</span
                >
                <select
                  v-else-if="editForms[template.id].type === 'TRANSFER'"
                  v-model="editForms[template.id].transferDestinationId"
                  :aria-label="`${template.name}の振替先`"
                >
                  <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
                    {{ account.name }}
                  </option>
                </select>
                <select
                  v-else
                  v-model="editForms[template.id].paymentMethodId"
                  :aria-label="`${template.name}の支払い方法`"
                >
                  <option v-for="method in paymentMethods" :key="method.id" :value="method.id">
                    {{ method.name }}
                  </option>
                </select>
                <small
                  v-if="
                    editErrors[template.id]?.transferDestinationId ||
                    editErrors[template.id]?.paymentMethodId
                  "
                  class="field-error"
                  >{{
                    editErrors[template.id]?.transferDestinationId ??
                    editErrors[template.id]?.paymentMethodId
                  }}</small
                >
              </td>
              <td>
                <input
                  v-model.number="editForms[template.id].defaultAmount"
                  type="number"
                  min="1"
                  inputmode="numeric"
                  :aria-label="`${template.name}の標準金額`"
                />
                <small v-if="editErrors[template.id]?.defaultAmount" class="field-error">{{
                  editErrors[template.id]?.defaultAmount
                }}</small>
              </td>
              <td>
                <textarea
                  v-model="editForms[template.id].memo"
                  maxlength="500"
                  wrap="soft"
                  :aria-label="`${template.name}のメモ`"
                ></textarea>
                <small v-if="editErrors[template.id]?.memo" class="field-error">{{
                  editErrors[template.id]?.memo
                }}</small>
              </td>
              <td>
                <input
                  v-model.number="editForms[template.id].displayOrder"
                  type="number"
                  min="0"
                  inputmode="numeric"
                  :aria-label="`${template.name}の表示順`"
                />
                <small v-if="editErrors[template.id]?.displayOrder" class="field-error">{{
                  editErrors[template.id]?.displayOrder
                }}</small>
              </td>
              <td class="action-cell">
                <p v-if="rowErrors[template.id]" class="message error">
                  {{ rowErrors[template.id] }}
                </p>
                <div class="row-actions recurring-template-actions">
                  <button
                    type="button"
                    :disabled="updatingIds.has(template.id) || deletingIds.has(template.id)"
                    @click="updateTemplate(template)"
                  >
                    {{ updatingIds.has(template.id) ? '更新中...' : '更新' }}
                  </button>
                  <button
                    type="button"
                    class="danger-button"
                    :disabled="updatingIds.has(template.id) || deletingIds.has(template.id)"
                    @click="confirmDeleteTemplate(template)"
                  >
                    {{ deletingIds.has(template.id) ? '削除中...' : '削除' }}
                  </button>
                </div>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.recurring-candidates-table {
  min-width: 1440px;
}

.recurring-template-table {
  min-width: 1640px;
}

.recurring-candidates-table thead th,
.recurring-template-table thead th {
  white-space: nowrap;
}

.recurring-candidates-table th,
.recurring-candidates-table td,
.recurring-template-table th,
.recurring-template-table td {
  vertical-align: top;
}

.recurring-candidates-table tbody > tr > :is(:nth-child(1), :nth-child(2), :nth-child(9)),
.recurring-template-table tbody > tr > :is(:nth-child(2), :nth-child(10)) {
  vertical-align: middle;
}

.recurring-candidates-table
  tbody
  > tr
  > td:not([colspan]):is(:nth-child(3), :nth-child(4), :nth-child(7), :nth-child(8))::before,
.recurring-template-table
  tbody
  > tr
  > td:not([colspan]):is(
    :nth-child(1),
    :nth-child(3),
    :nth-child(4),
    :nth-child(7),
    :nth-child(8),
    :nth-child(9)
  )::before {
  display: block;
  height: 1rem;
  margin-bottom: 0.3rem;
  content: '';
}

.recurring-candidates-table :is(th, td):is(:nth-child(5), :nth-child(6)),
.recurring-template-table :is(th, td):is(:nth-child(5), :nth-child(6)) {
  min-width: 150px;
}

.recurring-template-table :is(th, td):nth-child(1) {
  min-width: 180px;
}

.recurring-template-table :is(th, td):nth-child(4) {
  min-width: 110px;
}

.recurring-template-table :is(th, td):nth-child(8) {
  min-width: 220px;
}

.recurring-template-table :is(th, td):nth-child(9) {
  min-width: 110px;
}

.recurring-template-table :is(th, td):nth-child(10) {
  min-width: 160px;
}

.recurring-candidates-table textarea,
.recurring-template-table textarea {
  min-width: 190px;
  height: 5rem;
  min-height: 5rem;
  overflow-x: hidden;
  overflow-y: auto;
  resize: vertical;
}

.recurring-candidates-table input[type='checkbox'],
.recurring-template-table input[type='checkbox'] {
  width: auto;
  min-height: auto;
}

.recurring-cell-label {
  display: block;
  min-height: 1rem;
  margin-bottom: 0.3rem;
  color: #64748b;
  font-size: 0.75rem;
  font-weight: 700;
  line-height: 1rem;
}

.not-applicable {
  display: inline-flex;
  min-height: 2.75rem;
  align-items: center;
  color: #64748b;
}

.registered-candidate-row {
  background: #f8fafc;
  color: #64748b;
}

.registered-badge {
  display: inline-flex;
  border-radius: 999px;
  padding: 0.2rem 0.55rem;
  background: #e2e8f0;
  color: #475569;
  font-size: 0.8rem;
  font-weight: 700;
  white-space: nowrap;
}

.recurring-register-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 0.75rem;
}

.recurring-template-form {
  display: grid;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  gap: 1rem;
  align-items: start;
}

.recurring-enabled-field,
.not-applicable-field,
.recurring-template-form .form-actions {
  align-self: end;
  min-height: 2.75rem;
}

.recurring-memo-field {
  grid-column: span 2;
}

.recurring-memo-field textarea {
  min-height: 5rem;
  overflow-x: hidden;
  overflow-y: auto;
}

.recurring-template-actions {
  flex-wrap: nowrap;
}

@media (max-width: 900px) {
  .recurring-template-form {
    grid-template-columns: repeat(2, minmax(180px, 1fr));
  }
}

@media (max-width: 640px) {
  .recurring-template-form {
    grid-template-columns: 1fr;
  }

  .recurring-memo-field {
    grid-column: auto;
  }

  .recurring-register-actions {
    justify-content: flex-start;
  }
}
</style>
