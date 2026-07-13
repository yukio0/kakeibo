<script setup lang="ts">
import { computed } from 'vue'
import type { DonutSegment } from '@/summary/donut'

const props = defineProps<{
  segments: DonutSegment[]
  total: number
  ariaLabel?: string
}>()

// r をこの値にすると円周がちょうど 100 になり、dasharray を「割合(%)」でそのまま書ける。
const RADIUS = 15.915494309189533
// スライス間の隙間(円周100に対する長さ)。1件だけのときは切らずに全周を塗る。
const SLICE_GAP = 0.6

const arcs = computed(() => {
  const nonzero = props.segments.filter((segment) => segment.value > 0)
  if (props.total <= 0 || nonzero.length === 0) {
    return []
  }

  let cumulative = 0
  return nonzero.map((segment) => {
    const percent = (segment.value / props.total) * 100
    const startPercent = cumulative
    cumulative += percent
    const length = nonzero.length > 1 ? Math.max(percent - SLICE_GAP, 0.001) : percent
    return {
      key: segment.key,
      color: segment.color,
      dashArray: `${length} ${100 - length}`,
      // dashoffset 25 で開始位置を真上(12時)に合わせる。
      dashOffset: 25 - startPercent,
    }
  })
})
</script>

<template>
  <svg class="donut" viewBox="0 0 42 42" role="img" :aria-label="ariaLabel">
    <circle class="donut-track" cx="21" cy="21" :r="RADIUS" fill="none" />
    <circle
      v-for="arc in arcs"
      :key="arc.key"
      class="donut-arc"
      cx="21"
      cy="21"
      :r="RADIUS"
      fill="none"
      :stroke="arc.color"
      :stroke-dasharray="arc.dashArray"
      :stroke-dashoffset="arc.dashOffset"
    />
  </svg>
</template>
