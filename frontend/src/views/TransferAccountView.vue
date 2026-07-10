<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  createTransferAccount,
  deleteTransferAccount,
  getTransferAccounts,
  type TransferAccount,
  type TransferAccountRequest,
  updateTransferAccount,
} from '@/api/kakeibo'

type TransferAccountForm = {
  name: string
  displayOrder: number
}

type TransferAccountField = keyof TransferAccountForm
type FieldErrors = Partial<Record<TransferAccountField, string>>

const AUTO_SAVE_DELAY_MS = 700

const transferAccounts = ref<TransferAccount[]>([])
const loading = ref(false)
const creating = ref(false)
const savingIds = ref<number[]>([])
const deletingIds = ref<number[]>([])
const listError = ref<string | null>(null)
const successMessage = ref<string | null>(null)

const createForm = reactive<TransferAccountForm>({
  name: '',
  displayOrder: 0,
})
const createErrors = ref<FieldErrors>({})
const editForms = reactive<Record<number, TransferAccountForm>>({})
const editErrors = reactive<Record<number, FieldErrors>>({})
const rowErrors = reactive<Record<number, string>>({})
const autoSaveTimers = new Map<number, number>()

const hasTransferAccounts = computed(() => transferAccounts.value.length > 0)

onMounted(() => {
  void loadTransferAccounts()
})

onBeforeUnmount(() => {
  autoSaveTimers.forEach((timer) => window.clearTimeout(timer))
  autoSaveTimers.clear()
})

async function loadTransferAccounts(): Promise<void> {
  loading.value = true
  listError.value = null

  try {
    transferAccounts.value = sortTransferAccounts(await getTransferAccounts())
    syncEditForms()
    createForm.displayOrder = nextDisplayOrder()
  } catch (error) {
    listError.value = toMessage(error)
  } finally {
    loading.value = false
  }
}

async function submitCreate(): Promise<void> {
  creating.value = true
  createErrors.value = {}
  listError.value = null
  successMessage.value = null

  try {
    const created = await createTransferAccount(toRequest(createForm))
    transferAccounts.value = sortTransferAccounts([...transferAccounts.value, created])
    syncEditForms()
    createForm.name = ''
    createForm.displayOrder = nextDisplayOrder()
    successMessage.value = '振替元・振替先を登録しました'
  } catch (error) {
    if (error instanceof ApiError) {
      createErrors.value = toFieldErrors(error)
    }
    listError.value = toMessage(error)
  } finally {
    creating.value = false
  }
}

async function saveTransferAccount(transferAccount: TransferAccount): Promise<void> {
  const form = editForms[transferAccount.id]
  if (!form || isDeleting(transferAccount.id)) {
    return
  }

  if (!isDirty(transferAccount)) {
    clearTransferAccountAutoSaveTimer(transferAccount.id)
    return
  }

  if (isSaving(transferAccount.id)) {
    scheduleTransferAccountAutoSave(transferAccount)
    return
  }

  clearTransferAccountAutoSaveTimer(transferAccount.id)
  const request = toRequest(form)

  setBusy(savingIds, transferAccount.id, true)
  editErrors[transferAccount.id] = {}
  delete rowErrors[transferAccount.id]
  successMessage.value = null

  try {
    const updated = await updateTransferAccount(transferAccount.id, request)
    transferAccounts.value = sortTransferAccounts(
      transferAccounts.value.map((current) =>
        current.id === updated.id ? updated : current,
      ),
    )
    if (isSameRequest(form, request)) {
      editForms[transferAccount.id] = toForm(updated)
    } else {
      scheduleTransferAccountAutoSave(updated)
    }
  } catch (error) {
    if (!isSameRequest(form, request)) {
      scheduleTransferAccountAutoSave(transferAccount)
    } else if (error instanceof ApiError) {
      editErrors[transferAccount.id] = toFieldErrors(error)
      rowErrors[transferAccount.id] = toMessage(error)
    } else {
      rowErrors[transferAccount.id] = toMessage(error)
    }
  } finally {
    setBusy(savingIds, transferAccount.id, false)
  }
}

function scheduleTransferAccountAutoSave(transferAccount: TransferAccount): void {
  const currentTimer = autoSaveTimers.get(transferAccount.id)
  if (currentTimer !== undefined) {
    window.clearTimeout(currentTimer)
  }

  autoSaveTimers.set(
    transferAccount.id,
    window.setTimeout(() => {
      autoSaveTimers.delete(transferAccount.id)
      void saveTransferAccount(transferAccount)
    }, AUTO_SAVE_DELAY_MS),
  )
}

function clearTransferAccountAutoSaveTimer(id: number): void {
  const timer = autoSaveTimers.get(id)
  if (timer !== undefined) {
    window.clearTimeout(timer)
    autoSaveTimers.delete(id)
  }
}

async function confirmDelete(transferAccount: TransferAccount): Promise<void> {
  if (transferAccounts.value.length <= 1) {
    rowErrors[transferAccount.id] = '振替元・振替先は最低1件必要です'
    successMessage.value = null
    return
  }

  const confirmed = window.confirm(
    `振替元・振替先「${transferAccount.name}」を削除します。よろしいですか？`,
  )
  if (!confirmed) {
    return
  }

  setBusy(deletingIds, transferAccount.id, true)
  clearTransferAccountAutoSaveTimer(transferAccount.id)
  delete rowErrors[transferAccount.id]
  successMessage.value = null

  try {
    await deleteTransferAccount(transferAccount.id)
    transferAccounts.value = transferAccounts.value.filter(
      (current) => current.id !== transferAccount.id,
    )
    delete editForms[transferAccount.id]
    delete editErrors[transferAccount.id]
    clearTransferAccountAutoSaveTimer(transferAccount.id)
    successMessage.value = '振替元・振替先を削除しました'
  } catch (error) {
    rowErrors[transferAccount.id] = toMessage(error)
  } finally {
    setBusy(deletingIds, transferAccount.id, false)
  }
}

function isSaving(id: number): boolean {
  return savingIds.value.includes(id)
}

function isDeleting(id: number): boolean {
  return deletingIds.value.includes(id)
}

function isDirty(transferAccount: TransferAccount): boolean {
  const form = editForms[transferAccount.id]
  return (
    !!form &&
    (form.name !== transferAccount.name || form.displayOrder !== transferAccount.displayOrder)
  )
}

function nextDisplayOrder(): number {
  const orders = transferAccounts.value.map((transferAccount) => transferAccount.displayOrder)
  return Math.max(-10, ...orders) + 10
}

function syncEditForms(): void {
  transferAccounts.value.forEach((transferAccount) => {
    editForms[transferAccount.id] = toForm(transferAccount)
    editErrors[transferAccount.id] = editErrors[transferAccount.id] ?? {}
  })

  Object.keys(editForms).forEach((id) => {
    const transferAccountId = Number(id)
    if (
      !transferAccounts.value.some((transferAccount) => transferAccount.id === transferAccountId)
    ) {
      delete editForms[transferAccountId]
      delete editErrors[transferAccountId]
      delete rowErrors[transferAccountId]
      clearTransferAccountAutoSaveTimer(transferAccountId)
    }
  })
}

function toForm(transferAccount: TransferAccount): TransferAccountForm {
  return {
    name: transferAccount.name,
    displayOrder: transferAccount.displayOrder,
  }
}

function toRequest(form: TransferAccountForm): TransferAccountRequest {
  return {
    name: form.name.trim(),
    displayOrder: Number(form.displayOrder),
  }
}

function isSameRequest(
  form: TransferAccountForm,
  request: TransferAccountRequest,
): boolean {
  return (
    form.name.trim() === request.name &&
    Object.is(Number(form.displayOrder), request.displayOrder)
  )
}

function toFieldErrors(error: ApiError): FieldErrors {
  const result: FieldErrors = {}
  error.errors.forEach((fieldError) => {
    if (isTransferAccountField(fieldError.field)) {
      result[fieldError.field] = fieldError.message
    }
  })
  return result
}

function toMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  return '通信に失敗しました'
}

function isTransferAccountField(field: string): field is TransferAccountField {
  return field === 'name' || field === 'displayOrder'
}

function sortTransferAccounts(items: TransferAccount[]): TransferAccount[] {
  return [...items].sort((left, right) => {
    const displayOrder = left.displayOrder - right.displayOrder
    if (displayOrder !== 0) {
      return displayOrder
    }

    return left.id - right.id
  })
}

function setBusy(target: typeof savingIds, id: number, busy: boolean): void {
  target.value = busy ? [...target.value, id] : target.value.filter((current) => current !== id)
}
</script>

<template>
  <section class="page-heading">
    <h1>振替管理</h1>
    <p>銀行口座や財布など、振替元と振替先に使う資産を登録、編集、削除できます。</p>
  </section>

  <section class="status-card">
    <h2>振替元・振替先を追加</h2>
    <form class="form-grid payment-method-form-grid" @submit.prevent="submitCreate">
      <label class="field">
        <span>資産名</span>
        <input v-model="createForm.name" type="text" autocomplete="off">
        <small v-if="createErrors.name" class="field-error">{{ createErrors.name }}</small>
      </label>

      <label class="field">
        <span>表示順</span>
        <input v-model.number="createForm.displayOrder" type="number" min="0">
        <small v-if="createErrors.displayOrder" class="field-error">
          {{ createErrors.displayOrder }}
        </small>
      </label>

      <div class="form-actions">
        <button type="submit" :disabled="creating || loading">
          {{ creating ? '登録中...' : '登録' }}
        </button>
      </div>
    </form>

    <p v-if="listError" class="message error">{{ listError }}</p>
    <p v-if="successMessage" class="message success">{{ successMessage }}</p>
  </section>

  <section class="category-section" aria-labelledby="transfer-account-list">
    <div class="section-heading">
      <div>
        <p class="eyebrow">振替</p>
        <h2 id="transfer-account-list">振替元・振替先一覧</h2>
      </div>
      <span class="count-badge">{{ transferAccounts.length }}件</span>
    </div>

    <div class="table-wrap">
      <table class="category-table">
        <thead>
          <tr>
            <th scope="col" class="name-cell">資産名</th>
            <th scope="col" class="order-cell">表示順</th>
            <th scope="col" class="action-cell">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="transferAccounts.length === 0">
            <td colspan="3" class="empty-cell">振替元・振替先はまだありません。</td>
          </tr>
          <tr v-for="transferAccount in transferAccounts" :key="transferAccount.id">
            <template v-if="editForms[transferAccount.id]">
              <td class="name-cell">
                <input
                  v-model="editForms[transferAccount.id].name"
                  type="text"
                  autocomplete="off"
                  @input="scheduleTransferAccountAutoSave(transferAccount)"
                  @blur="saveTransferAccount(transferAccount)"
                >
                <small v-if="editErrors[transferAccount.id]?.name" class="field-error">
                  {{ editErrors[transferAccount.id]?.name }}
                </small>
              </td>
              <td class="order-cell">
                <input
                  v-model.number="editForms[transferAccount.id].displayOrder"
                  type="number"
                  min="0"
                  @input="scheduleTransferAccountAutoSave(transferAccount)"
                  @blur="saveTransferAccount(transferAccount)"
                >
                <small v-if="editErrors[transferAccount.id]?.displayOrder" class="field-error">
                  {{ editErrors[transferAccount.id]?.displayOrder }}
                </small>
              </td>
              <td class="action-cell">
                <p v-if="rowErrors[transferAccount.id]" class="message error">
                  {{ rowErrors[transferAccount.id] }}
                </p>
                <div class="row-actions">
                  <button
                    type="button"
                    class="danger-button"
                    :disabled="isSaving(transferAccount.id) || isDeleting(transferAccount.id)"
                    @click="confirmDelete(transferAccount)"
                  >
                    {{ isDeleting(transferAccount.id) ? '削除中...' : '削除' }}
                  </button>
                </div>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section v-if="!loading && !hasTransferAccounts" class="status-card">
    <p>振替元・振替先が未登録です。上のフォームから登録してください。</p>
  </section>
</template>
