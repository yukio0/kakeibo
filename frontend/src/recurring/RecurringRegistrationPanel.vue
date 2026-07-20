<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { toMessage } from '@/api/http'
import {
  getRecurringTransactionCandidates,
  registerRecurringTransactions,
  type Category,
  type PaymentMethod,
  type TransferAccount,
} from '@/api/kakeibo'
import {
  applyTypeDefaults,
  categoriesForType,
  primaryLabel,
  secondaryLabel,
  toCandidateDraft,
  toRegistrationItem,
  validateCandidate,
  type CandidateDraft,
  type CandidateErrors,
  type RecurringMasterData,
} from '@/recurring/formModel'
import { pad2 } from '@/transactions/dates'

const props = defineProps<{
  year: number
  month: number
  loading: boolean
  categories: Category[]
  paymentMethods: PaymentMethod[]
  transferAccounts: TransferAccount[]
}>()

const emit = defineEmits<{
  periodChange: [period: { year: number; month: number }]
  announce: [message: string]
}>()

const candidates = ref<CandidateDraft[] | null>(null)
const candidateErrors = reactive<Record<number, CandidateErrors>>({})
const confirming = ref(false)
const registering = ref(false)
const candidateError = ref<string | null>(null)

const masters = computed<RecurringMasterData>(() => ({
  categories: props.categories,
  paymentMethods: props.paymentMethods,
  transferAccounts: props.transferAccounts,
}))
const monthInputValue = computed(() => `${props.year}-${pad2(props.month)}`)
const monthStartDate = computed(() => `${monthInputValue.value}-01`)
const monthEndDate = computed(() => {
  const lastDay = new Date(props.year, props.month, 0).getDate()
  return `${monthInputValue.value}-${pad2(lastDay)}`
})
const selectedCount = computed(
  () =>
    candidates.value?.filter((candidate) => candidate.selected && !candidate.registered).length ??
    0,
)

defineExpose({ clearCandidates })

async function confirmCandidates(): Promise<void> {
  confirming.value = true
  candidateError.value = null
  emit('announce', '')
  clearCandidateErrors()
  try {
    const result = await getRecurringTransactionCandidates(props.year, props.month)
    candidates.value = result.items.map(toCandidateDraft)
    emit('announce', `${props.year}年${props.month}月の登録内容を表示しました`)
  } catch (error) {
    candidates.value = null
    candidateError.value = toMessage(error)
  } finally {
    confirming.value = false
  }
}

function changeCandidateType(candidate: CandidateDraft): void {
  applyTypeDefaults(candidate, masters.value)
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
    const errors = validateCandidate(
      candidate,
      masters.value,
      monthStartDate.value,
      monthEndDate.value,
    )
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
      year: props.year,
      month: props.month,
      items: selected.map(toRegistrationItem),
    })
    await confirmCandidates()
    const skipped = result.skippedTemplateIds.length
    emit(
      'announce',
      skipped === 0
        ? `${result.created.length}件の定期取引を登録しました`
        : `${result.created.length}件を登録しました。${skipped}件は登録済みのためスキップしました`,
    )
  } catch (error) {
    candidateError.value = toMessage(error)
  } finally {
    registering.value = false
  }
}

function handleMonthInput(event: Event): void {
  const input = event.target as HTMLInputElement
  const parsed = parseMonthValue(input.value)
  if (!parsed) {
    input.value = monthInputValue.value
    return
  }
  clearCandidates()
  emit('announce', '')
  emit('periodChange', parsed)
}

function clearCandidates(): void {
  candidates.value = null
  candidateError.value = null
  clearCandidateErrors()
}

function clearCandidateErrors(): void {
  Object.keys(candidateErrors).forEach((key) => delete candidateErrors[Number(key)])
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
</script>

<template>
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
                    v-for="category in categoriesForType(candidate.type, masters)"
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
</template>
