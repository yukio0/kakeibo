<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { toMessage } from '@/api/http'
import {
  getMonthlyBudget,
  getMonthlyCategoryExpenses,
  getMonthlyDailySummary,
  getMonthlyTrend,
  updateMonthlyBudget,
  type CategoryExpenseSummary,
  type DailySummary,
  type MonthlyBudget,
  type MonthlyBudgetRequest,
  type MonthlySummary,
} from '@/api/kakeibo'
import { pad2 } from '@/transactions/dates'
import DonutChart from '@/summary/DonutChart.vue'
import TrendChart from '@/summary/TrendChart.vue'
import type { DonutSegment } from '@/summary/donut'

const TREND_MONTHS = 12

// 検証済みカテゴリパレット(dataviz)。8スロットを固定順で使い、超過分は「その他」に集約する。
const CATEGORY_PALETTE = [
  '#2a78d6',
  '#1baf7a',
  '#eda100',
  '#008300',
  '#4a3aa7',
  '#e34948',
  '#e87ba4',
  '#eb6834',
]
const OTHER_COLOR = '#94a3b8'
const MAX_SLICES = CATEGORY_PALETTE.length
const MAX_BUDGET_AMOUNT = 2_147_483_647

type BudgetSaveSnapshot = {
  periodKey: string
  requestKey: string
  request: MonthlyBudgetRequest
}

const today = new Date()
const period = reactive({
  year: today.getFullYear(),
  month: today.getMonth() + 1,
})

const summary = ref<CategoryExpenseSummary | null>(null)
const trend = ref<MonthlySummary[]>([])
const dailySummary = ref<DailySummary | null>(null)
const budget = ref<MonthlyBudget | null>(null)
const overallBudgetInput = ref<string | number>('')
const categoryBudgetInputs = reactive<Record<number, string | number>>({})
const loading = ref(true)
const loadError = ref<string | null>(null)
const budgetValidationError = ref<string | null>(null)
const budgetSaveError = ref<string | null>(null)

let loadSequence = 0
let activeBudgetSave: BudgetSaveSnapshot | null = null
const pendingBudgetSaves: BudgetSaveSnapshot[] = []
const lastSavedBudgetKeys = new Map<string, string>()
const latestBudgetResponses = new Map<string, MonthlyBudget>()
const budgetSaveVersions = new Map<string, number>()

const monthInputValue = computed(() => `${period.year}-${pad2(period.month)}`)
const periodLabel = computed(() => `${period.year}年${period.month}月`)
const expenseTotal = computed(() => summary.value?.expenseTotal ?? 0)

type LegendRow = DonutSegment & { percent: number }

const rows = computed<LegendRow[]>(() => {
  const total = expenseTotal.value
  const categories = summary.value?.categories ?? []
  if (categories.length === 0 || total <= 0) {
    return []
  }

  const withinLimit =
    categories.length <= MAX_SLICES ? categories : categories.slice(0, MAX_SLICES - 1)
  const segments: LegendRow[] = withinLimit.map((category, index) => ({
    key: category.categoryId,
    label: category.categoryName,
    value: category.total,
    color: CATEGORY_PALETTE[index],
    percent: (category.total / total) * 100,
  }))

  if (categories.length > MAX_SLICES) {
    const otherTotal = categories
      .slice(MAX_SLICES - 1)
      .reduce((sum, category) => sum + category.total, 0)
    segments.push({
      key: 'other',
      label: 'その他',
      value: otherTotal,
      color: OTHER_COLOR,
      percent: (otherTotal / total) * 100,
    })
  }

  return segments
})

// 実際に返ってきた月数(最初に記録がある月〜対象月、最大 TREND_MONTHS か月)を見出しに使う。
const trendMonthCount = computed(() => trend.value.length || TREND_MONTHS)

const hasData = computed(() => rows.value.length > 0)
const hasTrendData = computed(() =>
  trend.value.some((month) => month.incomeTotal > 0 || month.expenseTotal > 0),
)
const dailyRows = computed(() => dailySummary.value?.days ?? [])
const hasDailyData = computed(() => dailyRows.value.length > 0)
const budgetRows = computed(() => budget.value?.categories ?? [])
const budgetMessage = computed(() => budgetValidationError.value ?? budgetSaveError.value)
const overallUsagePercent = computed(() => {
  const overallBudget = budget.value?.overallBudget
  if (overallBudget === null || overallBudget === undefined) {
    return 0
  }
  return (budget.value!.spentAmount / overallBudget) * 100
})
const overallProgressWidth = computed(() => `${Math.min(overallUsagePercent.value, 100)}%`)

onMounted(() => {
  void loadSummary()
})

async function loadSummary(): Promise<void> {
  const requestSequence = ++loadSequence
  const year = period.year
  const month = period.month
  const requestedPeriodKey = toPeriodKey(year, month)
  const budgetSaveVersion = budgetSaveVersions.get(requestedPeriodKey) ?? 0

  loading.value = true
  loadError.value = null
  budgetValidationError.value = null
  budgetSaveError.value = null
  try {
    const [categorySummary, trendSummary, daily, monthlyBudget] = await Promise.all([
      getMonthlyCategoryExpenses(year, month),
      getMonthlyTrend(year, month, TREND_MONTHS),
      getMonthlyDailySummary(year, month),
      getMonthlyBudget(year, month),
    ])

    if (requestSequence !== loadSequence) {
      return
    }

    summary.value = categorySummary
    trend.value = trendSummary.months
    dailySummary.value = daily

    const budgetChangedWhileLoading =
      (budgetSaveVersions.get(requestedPeriodKey) ?? 0) !== budgetSaveVersion
    if (!budgetChangedWhileLoading) {
      latestBudgetResponses.set(requestedPeriodKey, monthlyBudget)
    }
    const newestBudget = budgetChangedWhileLoading
      ? (latestBudgetResponses.get(requestedPeriodKey) ?? monthlyBudget)
      : monthlyBudget
    applyLoadedBudget(newestBudget, findOutstandingBudgetRequest(requestedPeriodKey))
  } catch (error) {
    if (requestSequence !== loadSequence) {
      return
    }

    summary.value = null
    trend.value = []
    dailySummary.value = null
    budget.value = null
    loadError.value = toMessage(error)
  } finally {
    if (requestSequence === loadSequence) {
      loading.value = false
    }
  }
}

function moveMonth(offset: number): void {
  const nextDate = new Date(period.year, period.month - 1 + offset, 1)
  period.year = nextDate.getFullYear()
  period.month = nextDate.getMonth() + 1
  void loadSummary()
}

function handleMonthInput(event: Event): void {
  const input = event.target as HTMLInputElement
  if (!input.value) {
    input.value = monthInputValue.value
    return
  }
  const [year, month] = input.value.split('-').map(Number)
  period.year = year
  period.month = month
  void loadSummary()
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('ja-JP', {
    style: 'currency',
    currency: 'JPY',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatOptionalCurrency(value: number | null): string {
  return value === null ? '未設定' : formatCurrency(value)
}

function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`
}

function formatMonthDay(date: string): string {
  const [, month, day] = date.split('-')
  return `${Number(month)}/${Number(day)}`
}

function applyLoadedBudget(
  monthlyBudget: MonthlyBudget,
  inputRequest?: MonthlyBudgetRequest,
): void {
  budget.value = monthlyBudget
  const formRequest = inputRequest ?? budgetResponseToRequest(monthlyBudget)
  overallBudgetInput.value = toBudgetInput(formRequest.overallBudget)
  const categoryAmounts = new Map(
    formRequest.categoryBudgets.map((category) => [category.categoryId, category.amount]),
  )

  Object.keys(categoryBudgetInputs).forEach((key) => {
    delete categoryBudgetInputs[Number(key)]
  })
  monthlyBudget.categories.forEach((category) => {
    categoryBudgetInputs[category.categoryId] = toBudgetInput(
      categoryAmounts.get(category.categoryId) ?? null,
    )
  })

  if (!inputRequest) {
    const request = budgetResponseToRequest(monthlyBudget)
    lastSavedBudgetKeys.set(
      toPeriodKey(monthlyBudget.year, monthlyBudget.month),
      toRequestKey(request),
    )
  }
}

function toBudgetInput(value: number | null): string {
  return value === null ? '' : String(value)
}

function budgetResponseToRequest(monthlyBudget: MonthlyBudget): MonthlyBudgetRequest {
  return {
    year: monthlyBudget.year,
    month: monthlyBudget.month,
    overallBudget: monthlyBudget.overallBudget,
    categoryBudgets: monthlyBudget.categories.flatMap((category) =>
      category.budgetAmount === null
        ? []
        : [{ categoryId: category.categoryId, amount: category.budgetAmount }],
    ),
  }
}

function handleBudgetInput(): void {
  budgetValidationError.value = null
  budgetSaveError.value = null
}

function handleBudgetCommit(event: Event): void {
  const input = event.currentTarget as HTMLInputElement
  if (!input.validity.valid) {
    budgetValidationError.value = `予算は1円以上${MAX_BUDGET_AMOUNT.toLocaleString('ja-JP')}円以下の整数で入力するか、空欄にしてください。`
    return
  }

  const snapshot = createBudgetSaveSnapshot()
  if (!snapshot) {
    return
  }

  budgetValidationError.value = null
  enqueueBudgetSave(snapshot)
}

function createBudgetSaveSnapshot(): BudgetSaveSnapshot | null {
  const monthlyBudget = budget.value
  if (!monthlyBudget) {
    return null
  }

  try {
    const request = buildBudgetRequest(monthlyBudget)

    return {
      periodKey: toPeriodKey(request.year, request.month),
      requestKey: toRequestKey(request),
      request,
    }
  } catch (error) {
    budgetValidationError.value =
      error instanceof Error ? error.message : '予算を確認してください。'
    return null
  }
}

function buildBudgetRequest(monthlyBudget: MonthlyBudget): MonthlyBudgetRequest {
  return {
    year: period.year,
    month: period.month,
    overallBudget: parseBudgetAmount(overallBudgetInput.value, '月全体の予算'),
    categoryBudgets: monthlyBudget.categories.flatMap((category) => {
      const amount = parseBudgetAmount(
        categoryBudgetInputs[category.categoryId] ?? '',
        `${category.categoryName}の予算`,
      )
      return amount === null ? [] : [{ categoryId: category.categoryId, amount }]
    }),
  }
}

function parseBudgetAmount(value: string | number, label: string): number | null {
  const trimmed = String(value).trim()
  if (trimmed === '') {
    return null
  }

  if (!/^\d+$/.test(trimmed)) {
    throw new Error(`${label}は1円以上の整数で入力するか、空欄にしてください。`)
  }

  const amount = Number(trimmed)
  if (!Number.isSafeInteger(amount) || amount < 1 || amount > MAX_BUDGET_AMOUNT) {
    throw new Error(
      `${label}は1円以上${MAX_BUDGET_AMOUNT.toLocaleString('ja-JP')}円以下で入力してください。`,
    )
  }

  return amount
}

function enqueueBudgetSave(snapshot: BudgetSaveSnapshot): void {
  const pendingIndex = pendingBudgetSaves.findIndex(
    (pending) => pending.periodKey === snapshot.periodKey,
  )
  if (pendingIndex >= 0) {
    if (pendingBudgetSaves[pendingIndex].requestKey === snapshot.requestKey) {
      return
    }
    pendingBudgetSaves.splice(pendingIndex, 1)
  }

  const activeForSamePeriod = activeBudgetSave?.periodKey === snapshot.periodKey
  if (activeForSamePeriod && activeBudgetSave?.requestKey === snapshot.requestKey) {
    return
  }
  if (!activeForSamePeriod && lastSavedBudgetKeys.get(snapshot.periodKey) === snapshot.requestKey) {
    return
  }

  pendingBudgetSaves.push(snapshot)
  void processPendingBudgetSaves()
}

async function processPendingBudgetSaves(): Promise<void> {
  if (activeBudgetSave || pendingBudgetSaves.length === 0) {
    return
  }

  const snapshot = pendingBudgetSaves.shift()!
  activeBudgetSave = snapshot

  try {
    const savedBudget = await updateMonthlyBudget(snapshot.request)
    lastSavedBudgetKeys.set(snapshot.periodKey, snapshot.requestKey)
    latestBudgetResponses.set(snapshot.periodKey, savedBudget)
    budgetSaveVersions.set(
      snapshot.periodKey,
      (budgetSaveVersions.get(snapshot.periodKey) ?? 0) + 1,
    )
    budgetSaveError.value = null

    const hasNewerSave = pendingBudgetSaves.some(
      (pending) => pending.periodKey === snapshot.periodKey,
    )
    if (toPeriodKey(period.year, period.month) === snapshot.periodKey && !hasNewerSave) {
      if (currentBudgetRequestKey() === snapshot.requestKey) {
        applyLoadedBudget(savedBudget)
      } else {
        // 入力値はユーザーが編集中のため、フォームを戻さず集計値だけ更新する。
        budget.value = savedBudget
      }
    }
  } catch (error) {
    budgetSaveError.value =
      `${snapshot.request.year}年${snapshot.request.month}月の予算を保存できませんでした。` +
      toMessage(error)
  } finally {
    activeBudgetSave = null
    void processPendingBudgetSaves()
  }
}

function currentBudgetRequestKey(): string | null {
  const monthlyBudget = budget.value
  if (
    !monthlyBudget ||
    monthlyBudget.year !== period.year ||
    monthlyBudget.month !== period.month
  ) {
    return null
  }

  try {
    return toRequestKey(buildBudgetRequest(monthlyBudget))
  } catch {
    return null
  }
}

function findOutstandingBudgetRequest(periodKey: string): MonthlyBudgetRequest | undefined {
  const pending = [...pendingBudgetSaves]
    .reverse()
    .find((snapshot) => snapshot.periodKey === periodKey)
  if (pending) {
    return pending.request
  }
  return activeBudgetSave?.periodKey === periodKey ? activeBudgetSave.request : undefined
}

function toPeriodKey(year: number, month: number): string {
  return `${year}-${pad2(month)}`
}

function toRequestKey(request: MonthlyBudgetRequest): string {
  return JSON.stringify({
    ...request,
    categoryBudgets: [...request.categoryBudgets].sort(
      (left, right) => left.categoryId - right.categoryId,
    ),
  })
}
</script>

<template>
  <section class="page-heading">
    <h1>集計</h1>
    <p>月ごとの支出をカテゴリ別の円グラフで確認できます。</p>
  </section>

  <section class="status-card">
    <div class="month-toolbar summary-month-toolbar">
      <button type="button" class="secondary-button" :disabled="loading" @click="moveMonth(-1)">
        前月
      </button>
      <label class="month-picker">
        <span>対象月</span>
        <input
          type="month"
          required
          :value="monthInputValue"
          :disabled="loading"
          @change="handleMonthInput"
        />
      </label>
      <button type="button" class="secondary-button" :disabled="loading" @click="moveMonth(1)">
        次月
      </button>
    </div>
  </section>

  <p v-if="loadError" class="message error" role="alert">{{ loadError }}</p>

  <section class="category-section budget-section" aria-labelledby="monthly-budget-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">予算</p>
        <h2 id="monthly-budget-heading">{{ periodLabel }}の予算管理</h2>
      </div>
    </div>

    <p v-show="budgetMessage" id="budget-error" class="message error" role="alert">
      {{ budgetMessage }}
    </p>
    <p v-if="loading" class="empty-cell">読み込み中...</p>

    <template v-else-if="budget">
      <div class="budget-overall">
        <div class="budget-overall-grid">
          <label class="budget-metric budget-input-metric">
            <span>月全体の予算</span>
            <span class="budget-amount-input">
              <input
                v-model="overallBudgetInput"
                type="number"
                inputmode="numeric"
                min="1"
                :max="MAX_BUDGET_AMOUNT"
                step="1"
                aria-label="月全体の予算"
                aria-describedby="budget-input-help budget-error"
                @input="handleBudgetInput"
                @change="handleBudgetCommit"
                @blur="handleBudgetCommit"
              />
              <span aria-hidden="true">円</span>
            </span>
            <small id="budget-input-help">空欄にすると未設定になります</small>
          </label>

          <div class="budget-metric">
            <span>使用額</span>
            <strong>{{ formatCurrency(budget.spentAmount) }}</strong>
          </div>
          <div class="budget-metric">
            <span>残額</span>
            <strong>{{ formatOptionalCurrency(budget.remainingAmount) }}</strong>
          </div>
          <div class="budget-metric">
            <span>超過額</span>
            <strong :class="{ 'budget-over-value': (budget.overAmount ?? 0) > 0 }">
              {{ formatOptionalCurrency(budget.overAmount) }}
            </strong>
          </div>
        </div>

        <div v-if="budget.overallBudget !== null" class="budget-progress-summary">
          <div
            class="budget-progress-track"
            role="progressbar"
            :aria-label="`${periodLabel}の月全体予算の使用状況`"
            aria-valuemin="0"
            :aria-valuemax="budget.overallBudget"
            :aria-valuenow="Math.min(budget.spentAmount, budget.overallBudget)"
            :aria-valuetext="`${formatCurrency(budget.overallBudget)}のうち${formatCurrency(budget.spentAmount)}を使用`"
          >
            <span
              class="budget-progress-value"
              :class="{ 'budget-progress-over': (budget.overAmount ?? 0) > 0 }"
              :style="{ width: overallProgressWidth }"
              aria-hidden="true"
            />
          </div>
          <p>{{ formatPercent(overallUsagePercent) }}使用</p>
        </div>
      </div>

      <div class="budget-category-heading">
        <h3>カテゴリ別予算</h3>
        <p>金額を入力して確定すると、対象月の予算を自動保存します。</p>
      </div>

      <p v-if="budgetRows.length === 0" class="empty-cell">支出カテゴリがありません。</p>

      <div v-else class="table-wrap">
        <table class="category-table budget-table">
          <thead>
            <tr>
              <th scope="col">カテゴリ</th>
              <th scope="col">予算</th>
              <th scope="col" class="numeric-cell">使用額</th>
              <th scope="col" class="numeric-cell">残額</th>
              <th scope="col" class="numeric-cell">超過額</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="category in budgetRows" :key="category.categoryId">
              <th scope="row">{{ category.categoryName }}</th>
              <td class="budget-input-cell">
                <span class="budget-amount-input">
                  <input
                    v-model="categoryBudgetInputs[category.categoryId]"
                    type="number"
                    inputmode="numeric"
                    min="1"
                    :max="MAX_BUDGET_AMOUNT"
                    step="1"
                    :aria-label="`${category.categoryName}の予算`"
                    aria-describedby="budget-input-help budget-error"
                    @input="handleBudgetInput"
                    @change="handleBudgetCommit"
                    @blur="handleBudgetCommit"
                  />
                  <span aria-hidden="true">円</span>
                </span>
              </td>
              <td class="numeric-cell">{{ formatCurrency(category.spentAmount) }}</td>
              <td class="numeric-cell">
                {{ formatOptionalCurrency(category.remainingAmount) }}
              </td>
              <td
                class="numeric-cell"
                :class="{ 'budget-over-value': (category.overAmount ?? 0) > 0 }"
              >
                {{ formatOptionalCurrency(category.overAmount) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>

  <section class="category-section">
    <div class="section-heading">
      <div>
        <p class="eyebrow">支出内訳</p>
        <h2>{{ periodLabel }}のカテゴリ別支出</h2>
      </div>
      <span class="count-badge">支出合計 {{ formatCurrency(expenseTotal) }}</span>
    </div>

    <p v-if="loading" class="empty-cell">読み込み中...</p>
    <p v-else-if="!hasData" class="empty-cell">この月の支出データはありません。</p>

    <div v-else class="summary-layout">
      <div class="donut-figure">
        <DonutChart :segments="rows" :total="expenseTotal" aria-label="カテゴリ別支出の円グラフ" />
        <div class="donut-center">
          <span class="donut-center-label">支出合計</span>
          <strong class="donut-center-value">{{ formatCurrency(expenseTotal) }}</strong>
        </div>
      </div>

      <div class="table-wrap category-legend-wrap">
        <table class="category-table category-legend">
          <thead>
            <tr>
              <th scope="col">カテゴリ</th>
              <th scope="col" class="numeric-cell">金額</th>
              <th scope="col" class="numeric-cell">割合</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.key">
              <td>
                <span
                  class="legend-swatch"
                  :style="{ backgroundColor: row.color }"
                  aria-hidden="true"
                />
                {{ row.label }}
              </td>
              <td class="numeric-cell">{{ formatCurrency(row.value) }}</td>
              <td class="numeric-cell">{{ formatPercent(row.percent) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>

  <section class="category-section">
    <div class="section-heading">
      <div>
        <p class="eyebrow">推移</p>
        <h2>直近{{ trendMonthCount }}か月の収支推移</h2>
      </div>
    </div>

    <p v-if="loading" class="empty-cell">読み込み中...</p>

    <template v-else>
      <p v-if="!hasTrendData" class="empty-cell">表示できる推移データがありません。</p>

      <div v-else>
        <ul class="trend-legend">
          <li><span class="legend-swatch trend-swatch-income" aria-hidden="true" />収入</li>
          <li><span class="legend-swatch trend-swatch-expense" aria-hidden="true" />支出</li>
          <li><span class="trend-swatch-line" aria-hidden="true" />差額</li>
        </ul>

        <div class="trend-chart-wrap">
          <TrendChart :months="trend" />
        </div>

        <div class="table-wrap trend-table-wrap">
          <table class="category-table trend-table">
            <thead>
              <tr>
                <th scope="col">月</th>
                <th scope="col" class="numeric-cell">収入</th>
                <th scope="col" class="numeric-cell">支出</th>
                <th scope="col" class="numeric-cell">差額</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="month in trend" :key="`${month.year}-${month.month}`">
                <td>{{ month.year }}年{{ month.month }}月</td>
                <td class="numeric-cell">{{ formatCurrency(month.incomeTotal) }}</td>
                <td class="numeric-cell">{{ formatCurrency(month.expenseTotal) }}</td>
                <td class="numeric-cell" :class="{ 'trend-negative': month.balance < 0 }">
                  {{ formatCurrency(month.balance) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="daily-summary">
        <div class="daily-summary-heading">
          <p class="eyebrow">日ごと</p>
          <h3>{{ periodLabel }}の日別収支</h3>
        </div>

        <p v-if="!hasDailyData" class="empty-cell">表示できる日別データがありません。</p>

        <div v-else class="table-wrap">
          <table class="category-table daily-table">
            <thead>
              <tr>
                <th scope="col">日付</th>
                <th scope="col" class="numeric-cell">収入</th>
                <th scope="col" class="numeric-cell">支出</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="day in dailyRows" :key="day.date">
                <td>{{ formatMonthDay(day.date) }}</td>
                <td class="numeric-cell">{{ formatCurrency(day.incomeTotal) }}</td>
                <td class="numeric-cell">{{ formatCurrency(day.expenseTotal) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </section>
</template>
