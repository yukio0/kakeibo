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
const AXIS_WIDTH = 32 // 左のY軸目盛りラベル用の余白
const PAD_RIGHT = 8
const PAD_TOP = 10
const PAD_BOTTOM = 18
const PLOT_HEIGHT = 150
const TARGET_TICKS = 4

// 目盛り幅を 1・2・2.5・5・10 ×10ⁿ のきりのいい値に丸める。
function niceStep(rough: number): number {
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)))
  const normalized = rough / magnitude
  const nice =
    normalized < 1.5 ? 1 : normalized < 3 ? 2 : normalized < 4 ? 2.5 : normalized < 7 ? 5 : 10
  return nice * magnitude
}

// 目盛りラベル。1万円以上は「◯万」、それ未満しかない場合は素の数値にフォールバックする。
function formatTick(value: number, maxAbs: number): string {
  if (value === 0) return '0'
  if (maxAbs < 10000) return new Intl.NumberFormat('ja-JP').format(value)
  const man = Math.round((value / 10000) * 10) / 10
  return `${man}万`
}

const layout = computed(() => {
  const months = props.months
  const width = AXIS_WIDTH + months.length * GROUP_WIDTH + PAD_RIGHT
  const height = PAD_TOP + PLOT_HEIGHT + PAD_BOTTOM
  const plotBottom = PAD_TOP + PLOT_HEIGHT
  const plotRight = width - PAD_RIGHT

  // 収入・支出は非負。差額のみ負になり得るので下側にも軸を伸ばす。
  const maxValue = Math.max(
    0,
    ...months.map((month) => Math.max(month.incomeTotal, month.expenseTotal, month.balance)),
  )
  const minValue = Math.min(0, ...months.map((month) => month.balance))

  // 目盛り幅を決め、スケールの上下端をきりのいい値に合わせる。
  const step = niceStep((maxValue - minValue || 1) / TARGET_TICKS)
  const domainMin = Math.floor(minValue / step) * step
  const domainMax = Math.max(Math.ceil(maxValue / step) * step, domainMin + step)
  const domainRange = domainMax - domainMin
  const scaleY = (value: number) => plotBottom - ((value - domainMin) / domainRange) * PLOT_HEIGHT
  const zeroY = scaleY(0)

  const maxAbs = Math.max(Math.abs(domainMin), Math.abs(domainMax))
  const ticks: { key: number; y: number; label: string }[] = []
  for (let value = domainMin; value <= domainMax + step / 2; value += step) {
    // 浮動小数の誤差で -0 などが出るのを避ける。
    const rounded = Math.round(value)
    ticks.push({ key: rounded, y: scaleY(rounded), label: formatTick(rounded, maxAbs) })
  }

  const bars = months.map((month, index) => {
    const center = AXIS_WIDTH + index * GROUP_WIDTH + GROUP_WIDTH / 2
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
    plotLeft: AXIS_WIDTH,
    plotRight,
    labelX: AXIS_WIDTH - 5,
    ticks,
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
    <g class="trend-y-axis">
      <template v-for="tick in layout.ticks" :key="tick.key">
        <line
          v-if="tick.key !== 0"
          class="trend-gridline"
          :x1="layout.plotLeft"
          :x2="layout.plotRight"
          :y1="tick.y"
          :y2="tick.y"
        />
        <text class="trend-y-label" :x="layout.labelX" :y="tick.y + 2.5">{{ tick.label }}</text>
      </template>
    </g>
    <line
      class="trend-baseline"
      :x1="layout.plotLeft"
      :x2="layout.plotRight"
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
