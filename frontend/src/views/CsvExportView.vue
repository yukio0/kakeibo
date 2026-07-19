<script setup lang="ts">
import { computed, ref } from 'vue'
import { toMessage } from '@/api/http'
import { exportTransactions, importTransactions, type TransactionImportResult } from '@/api/kakeibo'

const startDate = ref('')
const endDate = ref('')
const exporting = ref(false)
const errorMessage = ref<string | null>(null)
const message = ref<string | null>(null)

const importInput = ref<HTMLInputElement | null>(null)
const importFile = ref<File | null>(null)
const importResult = ref<TransactionImportResult | null>(null)
const importing = ref(false)
const importError = ref<string | null>(null)
const importMessage = ref<string | null>(null)

const importHasErrors = computed(() => (importResult.value?.errors.length ?? 0) > 0)
const canCommitImport = computed(
  () =>
    importFile.value !== null &&
    importResult.value !== null &&
    !importResult.value.committed &&
    importResult.value.errors.length === 0 &&
    importResult.value.months.length > 0,
)

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

async function onSelectImportFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  importFile.value = input.files?.[0] ?? null
  importResult.value = null
  importError.value = null
  importMessage.value = null
  if (importFile.value) {
    await previewImport()
  }
}

async function previewImport(): Promise<void> {
  if (!importFile.value || importing.value) {
    return
  }
  importing.value = true
  importError.value = null
  importMessage.value = null
  try {
    importResult.value = await importTransactions(importFile.value, false)
  } catch (error) {
    importResult.value = null
    importError.value = toMessage(error)
  } finally {
    importing.value = false
  }
}

async function confirmImport(): Promise<void> {
  if (!canCommitImport.value || importing.value) {
    return
  }
  importing.value = true
  importError.value = null
  importMessage.value = null
  try {
    const result = await importTransactions(importFile.value as File, true)
    if (result.committed) {
      importMessage.value = `${result.totalRows}件を取り込みました。`
      resetImport()
    } else {
      importResult.value = result
    }
  } catch (error) {
    importError.value = toMessage(error)
  } finally {
    importing.value = false
  }
}

function resetImport(): void {
  importFile.value = null
  importResult.value = null
  if (importInput.value) {
    importInput.value.value = ''
  }
}

function monthLabel(year: number, month: number): string {
  return `${year}年${month}月`
}
</script>

<template>
  <section class="csv-export-page">
    <div class="page-heading">
      <h1>CSV入出力</h1>
      <p>家計簿データをCSV形式で出力・取り込みします。</p>
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
        <form class="form-grid csv-period-form" @submit.prevent="exportPeriod">
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

    <section class="status-card">
      <div class="csv-export-option">
        <div>
          <h2>CSVから取り込み</h2>
          <p>
            エクスポート形式のCSVを取り込みます。<strong>CSVに含まれる月</strong>は、その内容で<strong>上書き</strong>されます（他の月はそのまま）。
          </p>
        </div>
        <div class="form-actions">
          <input
            ref="importInput"
            type="file"
            accept=".csv,text/csv"
            :disabled="importing"
            @change="onSelectImportFile"
          />
        </div>
      </div>

      <p v-if="importing" class="message">確認中...</p>
      <p v-if="importError" class="message error">{{ importError }}</p>
      <p v-if="importMessage" class="message success">{{ importMessage }}</p>

      <div v-if="importResult && importHasErrors" class="import-errors">
        <p class="message error">取り込めない行があります。修正して再度お試しください。</p>
        <ul>
          <li v-for="(error, index) in importResult.errors" :key="index">
            <template v-if="error.row > 0">{{ error.row }}行目: </template>{{ error.message }}
          </li>
        </ul>
      </div>

      <div v-else-if="canCommitImport && importResult" class="import-preview">
        <p>以下の内容で取り込みます。対象月の既存データは置き換えられます。</p>
        <div class="table-wrap">
          <table class="category-table import-plan-table">
            <thead>
              <tr>
                <th scope="col">対象月</th>
                <th scope="col" class="numeric-cell">既存</th>
                <th scope="col" class="numeric-cell">取り込み</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="plan in importResult.months" :key="`${plan.year}-${plan.month}`">
                <td>{{ monthLabel(plan.year, plan.month) }}</td>
                <td class="numeric-cell">{{ plan.replacedCount }}件</td>
                <td class="numeric-cell">{{ plan.importedCount }}件</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="form-actions">
          <button type="button" :disabled="importing" @click="confirmImport">
            {{ importing ? '取り込み中...' : '取り込む' }}
          </button>
        </div>
      </div>
    </section>
  </section>
</template>
