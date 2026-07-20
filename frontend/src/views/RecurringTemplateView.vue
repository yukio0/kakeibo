<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { toMessage } from '@/api/http'
import {
  getCategories,
  getPaymentMethods,
  getRecurringTransactionTemplates,
  getTransferAccounts,
  type Category,
  type PaymentMethod,
  type RecurringTransactionTemplate,
  type TransferAccount,
} from '@/api/kakeibo'
import { compareByDisplayOrder, compareCategories } from '@/masters'
import RecurringRegistrationPanel from '@/recurring/RecurringRegistrationPanel.vue'
import RecurringTemplateManager from '@/recurring/RecurringTemplateManager.vue'
import { pad2 } from '@/transactions/dates'

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
const registrationPanel = ref<InstanceType<typeof RecurringRegistrationPanel> | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const announcement = ref('')

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
    templates.value = templateItems
  } catch (error) {
    loadError.value = toMessage(error)
  } finally {
    loading.value = false
  }
}

function changePeriod(next: { year: number; month: number }): void {
  period.year = next.year
  period.month = next.month
  void router.replace({
    query: { ...route.query, month: `${next.year}-${pad2(next.month)}` },
  })
}

function clearCandidatesAfterDelete(): void {
  registrationPanel.value?.clearCandidates()
}

function parseMonthQuery(value: unknown): { year: number; month: number } | null {
  const queryValue = Array.isArray(value) ? value[0] : typeof value === 'string' ? value : ''
  const match = /^(\d{4})-(\d{2})$/.exec(queryValue)
  if (!match) {
    return null
  }
  const year = Number(match[1])
  const month = Number(match[2])
  return year >= 1 && month >= 1 && month <= 12 ? { year, month } : null
}
</script>

<template>
  <section class="page-heading">
    <h1>定期取引</h1>
    <p>給与、家賃、光熱費などのテンプレートから、確認した内容だけを今月分として登録できます。</p>
  </section>

  <p v-if="announcement" class="message success" aria-live="polite">{{ announcement }}</p>
  <p v-if="loadError" class="message error">{{ loadError }}</p>

  <RecurringRegistrationPanel
    ref="registrationPanel"
    :year="period.year"
    :month="period.month"
    :loading="loading"
    :categories="categories"
    :payment-methods="paymentMethods"
    :transfer-accounts="transferAccounts"
    @period-change="changePeriod"
    @announce="announcement = $event"
  />

  <RecurringTemplateManager
    :initial-templates="templates"
    :categories="categories"
    :payment-methods="paymentMethods"
    :transfer-accounts="transferAccounts"
    :loading="loading"
    @announce="announcement = $event"
    @template-deleted="clearCandidatesAfterDelete"
  />
</template>

<style>
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
