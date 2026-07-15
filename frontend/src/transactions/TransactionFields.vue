<script setup lang="ts">
import { computed } from 'vue'
import type { Category, PaymentMethod, TransferAccount } from '@/api/kakeibo'
import type { EditableField, TransactionFieldErrors, TransactionRow } from '@/transactions/rowModel'

const row = defineModel<TransactionRow>({ required: true })

const props = defineProps<{
  variant: 'row' | 'card'
  errors?: TransactionFieldErrors
  categoryOptions: (Category | TransferAccount)[]
  paymentMethodOptions: (PaymentMethod | TransferAccount)[]
  monthStartDate: string
  monthEndDate: string
  loading?: boolean
  saving?: boolean
  markNewRow?: boolean
}>()

const emit = defineEmits<{
  (event: 'type-change'): void
  (event: 'field-input', field: EditableField): void
  (event: 'field-focus'): void
}>()

const wrapperTag = computed(() => (props.variant === 'row' ? 'td' : 'label'))
const wrapperClass = computed(() => (props.variant === 'card' ? 'card-field' : undefined))
const isCard = computed(() => props.variant === 'card')

const categoryLabel = computed(() => (row.value.type === 'TRANSFER' ? '振替元' : 'カテゴリ'))
const paymentMethodLabel = computed(() => (row.value.type === 'TRANSFER' ? '振替先' : '支払い方法'))

const amountLikelyValid = computed(
  () =>
    typeof row.value.amount === 'number' &&
    Number.isInteger(row.value.amount) &&
    row.value.amount >= 1,
)

function isDisabled(field: EditableField): boolean {
  // 収入は支払い方法を持たないため、支払い方法は常に選択不可(空欄)にする。
  if (field === 'paymentMethodId' && row.value.type === 'INCOME') {
    return true
  }
  if (props.variant === 'card') {
    return false
  }
  return Boolean(
    props.loading ||
      props.saving ||
      (field === 'memo' && row.value.id === null && !amountLikelyValid.value),
  )
}
</script>

<template>
  <component :is="wrapperTag" :class="wrapperClass">
    <span v-if="isCard">日付</span>
    <input
      v-model="row.date"
      type="date"
      required
      :data-new-row-field="markNewRow ? 'date' : undefined"
      :class="{ 'cell-error': !!errors?.date }"
      :min="monthStartDate"
      :max="monthEndDate"
      :disabled="isDisabled('date')"
      @focus="emit('field-focus')"
      @input="emit('field-input', 'date')"
    />
    <small v-if="errors?.date" class="field-error">{{ errors.date }}</small>
  </component>
  <component :is="wrapperTag" :class="wrapperClass">
    <span v-if="isCard">種別</span>
    <select
      v-model="row.type"
      :class="{ 'cell-error': !!errors?.type }"
      :disabled="isDisabled('type')"
      @change="emit('type-change')"
    >
      <option value="EXPENSE">支出</option>
      <option value="INCOME">収入</option>
      <option value="TRANSFER">振替</option>
    </select>
    <small v-if="errors?.type" class="field-error">{{ errors.type }}</small>
  </component>
  <component :is="wrapperTag" :class="wrapperClass">
    <span v-if="isCard">{{ categoryLabel }}</span>
    <select
      v-model="row.categoryId"
      :class="{ 'cell-error': !!errors?.categoryId }"
      :disabled="isDisabled('categoryId')"
      @focus="emit('field-focus')"
      @change="emit('field-input', 'categoryId')"
    >
      <option v-for="option in categoryOptions" :key="option.id" :value="option.id">
        {{ option.name }}
      </option>
    </select>
    <small v-if="errors?.categoryId" class="field-error">{{ errors.categoryId }}</small>
  </component>
  <component :is="wrapperTag" :class="wrapperClass">
    <span v-if="isCard">{{ paymentMethodLabel }}</span>
    <select
      v-model="row.paymentMethodId"
      :class="{ 'cell-error': !!errors?.paymentMethodId }"
      :disabled="isDisabled('paymentMethodId')"
      @focus="emit('field-focus')"
      @change="emit('field-input', 'paymentMethodId')"
    >
      <option v-if="row.type === 'INCOME'" value="">—</option>
      <option v-for="option in paymentMethodOptions" :key="option.id" :value="option.id">
        {{ option.name }}
      </option>
    </select>
    <small v-if="errors?.paymentMethodId" class="field-error">{{ errors.paymentMethodId }}</small>
  </component>
  <component :is="wrapperTag" :class="wrapperClass">
    <span v-if="isCard">金額</span>
    <input
      v-model.number="row.amount"
      type="number"
      min="1"
      inputmode="numeric"
      :class="{ 'cell-error': !!errors?.amount }"
      :disabled="isDisabled('amount')"
      @focus="emit('field-focus')"
      @input="emit('field-input', 'amount')"
    />
    <small v-if="errors?.amount" class="field-error">{{ errors.amount }}</small>
  </component>
  <component :is="wrapperTag" :class="wrapperClass">
    <span v-if="isCard">メモ</span>
    <textarea
      v-model="row.memo"
      class="memo-textarea"
      maxlength="500"
      wrap="soft"
      :class="{ 'cell-error': !!errors?.memo }"
      :disabled="isDisabled('memo')"
      @focus="emit('field-focus')"
      @input="emit('field-input', 'memo')"
    />
    <small v-if="errors?.memo" class="field-error">{{ errors.memo }}</small>
  </component>
</template>
