<script setup lang="ts">
import { ref } from 'vue'
import { toMessage } from '@/api/http'
import { exportTransactions } from '@/api/kakeibo'

const startDate = ref('')
const endDate = ref('')
const exporting = ref(false)
const errorMessage = ref<string | null>(null)
const message = ref<string | null>(null)

async function exportAll(): Promise<void> {
  await downloadCsv()
}

async function exportPeriod(): Promise<void> {
  errorMessage.value = null
  message.value = null

  if (!startDate.value || !endDate.value) {
    errorMessage.value = '開始日と終了日を指定してください'
    return
  }
  if (endDate.value < startDate.value) {
    errorMessage.value = '終了日は開始日以降を指定してください'
    return
  }

  await downloadCsv(startDate.value, endDate.value)
}

async function downloadCsv(start?: string, end?: string): Promise<void> {
  if (exporting.value) {
    return
  }

  errorMessage.value = null
  message.value = null
  exporting.value = true
  try {
    const csv = await exportTransactions(start, end)
    if (!csv) {
      message.value = '期間内にデータがありません'
      return
    }

    const url = URL.createObjectURL(csv)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = csvFileName(start, end)
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    errorMessage.value = toMessage(error)
  } finally {
    exporting.value = false
  }
}

function csvFileName(start?: string, end?: string): string {
  if (!start || !end) {
    return 'kakeibo-all.csv'
  }
  return `kakeibo-${start}-${end}.csv`
}
</script>

<template>
  <section class="csv-export-page">
    <div class="page-heading">
      <h1>CSV出力</h1>
      <p>家計簿データをCSV形式で出力します。</p>
    </div>

    <section class="status-card">
      <div class="csv-export-option">
        <div>
          <h2>累積のCSVを出力</h2>
          <p>登録済みのすべての家計簿データを出力します。</p>
        </div>
        <div class="form-actions">
          <button type="button" :disabled="exporting" @click="exportAll">
            {{ exporting ? 'CSV出力中...' : '累積のCSVを出力' }}
          </button>
        </div>
      </div>

      <div class="csv-export-option">
        <div>
          <h2>指定した期間のCSVを出力</h2>
          <p>開始日と終了日の両方を指定してください。</p>
        </div>
        <form class="form-grid form-grid-compact" @submit.prevent="exportPeriod">
          <label class="field">
            <span>開始日</span>
            <input v-model="startDate" type="date" required :disabled="exporting" />
          </label>
          <label class="field">
            <span>終了日</span>
            <input v-model="endDate" type="date" required :disabled="exporting" />
          </label>
          <div class="form-actions">
            <button type="submit" :disabled="exporting">
              {{ exporting ? 'CSV出力中...' : '指定期間のCSVを出力' }}
            </button>
          </div>
        </form>
      </div>

      <p v-if="errorMessage" class="message error">{{ errorMessage }}</p>
      <p v-if="message" class="message">{{ message }}</p>
    </section>
  </section>
</template>
