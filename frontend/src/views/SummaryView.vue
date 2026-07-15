<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { toMessage } from '@/api/http'
import {
  getMonthlyCategoryExpenses,
  getMonthlyDailySummary,
  getMonthlyTrend,
  type CategoryExpenseSummary,
  type DailySummary,
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

const today = new Date()
const period = reactive({
  year: today.getFullYear(),
  month: today.getMonth() + 1,
})

const summary = ref<CategoryExpenseSummary | null>(null)
const trend = ref<MonthlySummary[]>([])
const dailySummary = ref<DailySummary | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)

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

onMounted(() => {
  void loadSummary()
})

async function loadSummary(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    const [categorySummary, trendSummary, daily] = await Promise.all([
      getMonthlyCategoryExpenses(period.year, period.month),
      getMonthlyTrend(period.year, period.month, TREND_MONTHS),
      getMonthlyDailySummary(period.year, period.month),
    ])
    summary.value = categorySummary
    trend.value = trendSummary.months
    dailySummary.value = daily
  } catch (error) {
    summary.value = null
    trend.value = []
    dailySummary.value = null
    loadError.value = toMessage(error)
  } finally {
    loading.value = false
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

function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`
}

function formatMonthDay(date: string): string {
  const [, month, day] = date.split('-')
  return `${Number(month)}/${Number(day)}`
}
</script>

<template>
  <section class="page-heading">
    <h1>集計</h1>
    <p>月ごとの支出をカテゴリ別の円グラフで確認できます。</p>
  </section>

  <section class="status-card">
    <div class="month-toolbar">
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

  <p v-if="loadError" class="message error">{{ loadError }}</p>

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
