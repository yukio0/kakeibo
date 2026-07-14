<script setup lang="ts">
import { computed } from 'vue'
import type { MonthlySummary } from '@/api/kakeibo'

const props = defineProps<{
  months: MonthlySummary[]
}>()

// すべて viewBox 内のユーザー単位。1 単位 = 1px でレンダリングし、
// コンテナ幅を超えるときだけ max-width で一様に縮小する(月数によらず棒の大きさは一定)。
const GROUP_WIDTH = 54
const BAR_WIDTH = 20
const BAR_GAP = 6
const PAD_LEFT = 8
const PAD_RIGHT = 8
const PAD_TOP = 10
const PAD_BOTTOM = 18
const PLOT_HEIGHT = 150

const layout = computed(() => {
  const months = props.months
  const width = PAD_LEFT + months.length * GROUP_WIDTH + PAD_RIGHT
  const height = PAD_TOP + PLOT_HEIGHT + PAD_BOTTOM
  const plotBottom = PAD_TOP + PLOT_HEIGHT

  // 収入・支出は非負。差額のみ負になり得るので下側の余白を確保する。
  const maxValue = Math.max(
    0,
    ...months.map((month) => Math.max(month.incomeTotal, month.expenseTotal, month.balance)),
  )
  const minValue = Math.min(0, ...months.map((month) => month.balance))
  const range = maxValue - minValue || 1
  const scaleY = (value: number) => plotBottom - ((value - minValue) / range) * PLOT_HEIGHT
  const zeroY = scaleY(0)

  const bars = months.map((month, index) => {
    const center = PAD_LEFT + index * GROUP_WIDTH + GROUP_WIDTH / 2
    const incomeY = scaleY(month.incomeTotal)
    const expenseY = scaleY(month.expenseTotal)
    return {
      key: `${month.year}-${month.month}`,
      label: `${month.month}月`,
      center,
      income: {
        x: center - BAR_GAP / 2 - BAR_WIDTH,
        y: incomeY,
        height: Math.max(zeroY - incomeY, 0),
      },
      expense: { x: center + BAR_GAP / 2, y: expenseY, height: Math.max(zeroY - expenseY, 0) },
      balanceY: scaleY(month.balance),
    }
  })

  return {
    width,
    height,
    zeroY,
    bars,
    barWidth: BAR_WIDTH,
    balancePoints: bars.map((bar) => `${bar.center},${bar.balanceY}`).join(' '),
  }
})
</script>

<template>
  <svg
    class="trend-chart"
    :viewBox="`0 0 ${layout.width} ${layout.height}`"
    :style="{ width: `${layout.width}px` }"
    preserveAspectRatio="xMidYMid meet"
    role="img"
    aria-label="月次の収入・支出・差額の推移グラフ"
  >
    <line
      class="trend-baseline"
      :x1="PAD_LEFT"
      :x2="layout.width - PAD_RIGHT"
      :y1="layout.zeroY"
      :y2="layout.zeroY"
    />
    <g v-for="bar in layout.bars" :key="bar.key">
      <rect
        class="trend-bar trend-bar-income"
        :x="bar.income.x"
        :y="bar.income.y"
        :width="layout.barWidth"
        :height="bar.income.height"
        rx="1"
      />
      <rect
        class="trend-bar trend-bar-expense"
        :x="bar.expense.x"
        :y="bar.expense.y"
        :width="layout.barWidth"
        :height="bar.expense.height"
        rx="1"
      />
      <text class="trend-x-label" :x="bar.center" :y="layout.height - 5">{{ bar.label }}</text>
    </g>
    <polyline class="trend-line-balance" :points="layout.balancePoints" fill="none" />
    <circle
      v-for="bar in layout.bars"
      :key="`dot-${bar.key}`"
      class="trend-dot-balance"
      :cx="bar.center"
      :cy="bar.balanceY"
      r="2.2"
    />
  </svg>
</template>
