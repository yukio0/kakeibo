<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  createPaymentMethod,
  deletePaymentMethod,
  getPaymentMethods,
  type PaymentMethod,
  type PaymentMethodRequest,
  updatePaymentMethod,
} from '@/api/kakeibo'

type PaymentMethodForm = {
  name: string
  displayOrder: number
}

type PaymentMethodField = keyof PaymentMethodForm
type FieldErrors = Partial<Record<PaymentMethodField, string>>

const AUTO_SAVE_DELAY_MS = 700

const paymentMethods = ref<PaymentMethod[]>([])
const loading = ref(false)
const creating = ref(false)
const savingIds = ref<number[]>([])
const deletingIds = ref<number[]>([])
const listError = ref<string | null>(null)
const successMessage = ref<string | null>(null)

const createForm = reactive<PaymentMethodForm>({
  name: '',
  displayOrder: 0,
})
const createErrors = ref<FieldErrors>({})
const editForms = reactive<Record<number, PaymentMethodForm>>({})
const editErrors = reactive<Record<number, FieldErrors>>({})
const rowErrors = reactive<Record<number, string>>({})
const autoSaveTimers = new Map<number, number>()

const hasPaymentMethods = computed(() => paymentMethods.value.length > 0)

onMounted(() => {
  void loadPaymentMethods()
})

onBeforeUnmount(() => {
  autoSaveTimers.forEach((timer) => window.clearTimeout(timer))
  autoSaveTimers.clear()
})

async function loadPaymentMethods(): Promise<void> {
  loading.value = true
  listError.value = null

  try {
    paymentMethods.value = sortPaymentMethods(await getPaymentMethods())
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
    const created = await createPaymentMethod(toRequest(createForm))
    paymentMethods.value = sortPaymentMethods([...paymentMethods.value, created])
    syncEditForms()
    createForm.name = ''
    createForm.displayOrder = nextDisplayOrder()
    successMessage.value = '支払い方法を登録しました'
  } catch (error) {
    if (error instanceof ApiError) {
      createErrors.value = toFieldErrors(error)
    }
    listError.value = toMessage(error)
  } finally {
    creating.value = false
  }
}

async function savePaymentMethod(paymentMethod: PaymentMethod): Promise<void> {
  const form = editForms[paymentMethod.id]
  if (!form || isDeleting(paymentMethod.id)) {
    return
  }

  if (!isDirty(paymentMethod)) {
    clearPaymentMethodAutoSaveTimer(paymentMethod.id)
    return
  }

  if (isSaving(paymentMethod.id)) {
    schedulePaymentMethodAutoSave(paymentMethod)
    return
  }

  clearPaymentMethodAutoSaveTimer(paymentMethod.id)
  const request = toRequest(form)

  setBusy(savingIds, paymentMethod.id, true)
  editErrors[paymentMethod.id] = {}
  delete rowErrors[paymentMethod.id]
  successMessage.value = null

  try {
    const updated = await updatePaymentMethod(paymentMethod.id, request)
    paymentMethods.value = sortPaymentMethods(
      paymentMethods.value.map((current) => (current.id === updated.id ? updated : current)),
    )
    if (isSameRequest(form, request)) {
      editForms[paymentMethod.id] = toForm(updated)
    } else {
      schedulePaymentMethodAutoSave(updated)
    }
  } catch (error) {
    if (!isSameRequest(form, request)) {
      schedulePaymentMethodAutoSave(paymentMethod)
    } else if (error instanceof ApiError) {
      editErrors[paymentMethod.id] = toFieldErrors(error)
      rowErrors[paymentMethod.id] = toMessage(error)
    } else {
      rowErrors[paymentMethod.id] = toMessage(error)
    }
  } finally {
    setBusy(savingIds, paymentMethod.id, false)
  }
}

function schedulePaymentMethodAutoSave(paymentMethod: PaymentMethod): void {
  const currentTimer = autoSaveTimers.get(paymentMethod.id)
  if (currentTimer !== undefined) {
    window.clearTimeout(currentTimer)
  }

  autoSaveTimers.set(
    paymentMethod.id,
    window.setTimeout(() => {
      autoSaveTimers.delete(paymentMethod.id)
      void savePaymentMethod(paymentMethod)
    }, AUTO_SAVE_DELAY_MS),
  )
}

function clearPaymentMethodAutoSaveTimer(id: number): void {
  const timer = autoSaveTimers.get(id)
  if (timer !== undefined) {
    window.clearTimeout(timer)
    autoSaveTimers.delete(id)
  }
}

async function confirmDelete(paymentMethod: PaymentMethod): Promise<void> {
  if (paymentMethods.value.length <= 1) {
    rowErrors[paymentMethod.id] = '支払い方法は最低1件必要です'
    successMessage.value = null
    return
  }

  const confirmed = window.confirm(
    `支払い方法「${paymentMethod.name}」を削除します。よろしいですか？`,
  )
  if (!confirmed) {
    return
  }

  setBusy(deletingIds, paymentMethod.id, true)
  clearPaymentMethodAutoSaveTimer(paymentMethod.id)
  delete rowErrors[paymentMethod.id]
  successMessage.value = null

  try {
    await deletePaymentMethod(paymentMethod.id)
    paymentMethods.value = paymentMethods.value.filter((current) => current.id !== paymentMethod.id)
    delete editForms[paymentMethod.id]
    delete editErrors[paymentMethod.id]
    clearPaymentMethodAutoSaveTimer(paymentMethod.id)
    successMessage.value = '支払い方法を削除しました'
  } catch (error) {
    rowErrors[paymentMethod.id] = toMessage(error)
  } finally {
    setBusy(deletingIds, paymentMethod.id, false)
  }
}

function isSaving(id: number): boolean {
  return savingIds.value.includes(id)
}

function isDeleting(id: number): boolean {
  return deletingIds.value.includes(id)
}

function isDirty(paymentMethod: PaymentMethod): boolean {
  const form = editForms[paymentMethod.id]
  return (
    !!form &&
    (form.name !== paymentMethod.name || form.displayOrder !== paymentMethod.displayOrder)
  )
}

function nextDisplayOrder(): number {
  const orders = paymentMethods.value.map((paymentMethod) => paymentMethod.displayOrder)
  return Math.max(-10, ...orders) + 10
}

function syncEditForms(): void {
  paymentMethods.value.forEach((paymentMethod) => {
    editForms[paymentMethod.id] = toForm(paymentMethod)
    editErrors[paymentMethod.id] = editErrors[paymentMethod.id] ?? {}
  })

  Object.keys(editForms).forEach((id) => {
    const paymentMethodId = Number(id)
    if (!paymentMethods.value.some((paymentMethod) => paymentMethod.id === paymentMethodId)) {
      delete editForms[paymentMethodId]
      delete editErrors[paymentMethodId]
      delete rowErrors[paymentMethodId]
      clearPaymentMethodAutoSaveTimer(paymentMethodId)
    }
  })
}

function toForm(paymentMethod: PaymentMethod): PaymentMethodForm {
  return {
    name: paymentMethod.name,
    displayOrder: paymentMethod.displayOrder,
  }
}

function toRequest(form: PaymentMethodForm): PaymentMethodRequest {
  return {
    name: form.name.trim(),
    displayOrder: Number(form.displayOrder),
  }
}

function isSameRequest(
  form: PaymentMethodForm,
  request: PaymentMethodRequest,
): boolean {
  return (
    form.name.trim() === request.name &&
    Object.is(Number(form.displayOrder), request.displayOrder)
  )
}

function toFieldErrors(error: ApiError): FieldErrors {
  const result: FieldErrors = {}
  error.errors.forEach((fieldError) => {
    if (isPaymentMethodField(fieldError.field)) {
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

function isPaymentMethodField(field: string): field is PaymentMethodField {
  return field === 'name' || field === 'displayOrder'
}

function sortPaymentMethods(items: PaymentMethod[]): PaymentMethod[] {
  return [...items].sort((left, right) => {
    const displayOrder = left.displayOrder - right.displayOrder
    if (displayOrder !== 0) {
      return displayOrder
    }

    return left.id - right.id
  })
}

function setBusy(target: typeof savingIds, id: number, busy: boolean): void {
  target.value = busy
    ? Array.from(new Set([...target.value, id]))
    : target.value.filter((current) => current !== id)
}
</script>

<template>
  <section class="page-heading">
    <p class="eyebrow">MASTER</p>
    <h1>支払い方法管理</h1>
    <p>現金、カード、PayPay など、家計簿入力で使う支払い方法を登録、編集、削除できます。</p>
  </section>

  <section class="status-card">
    <div>
      <p class="eyebrow">新規登録</p>
      <h2>支払い方法を追加</h2>
    </div>

    <form class="form-grid payment-method-form-grid" @submit.prevent="submitCreate">
      <label class="field">
        <span>支払い方法名</span>
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

  <section class="category-section" aria-labelledby="payment-method-list">
    <div class="section-heading">
      <div>
        <p class="eyebrow">支払い方法</p>
        <h2 id="payment-method-list">支払い方法一覧</h2>
      </div>
      <span class="count-badge">{{ paymentMethods.length }}件</span>
    </div>

    <div class="table-wrap">
      <table class="category-table">
        <thead>
          <tr>
            <th scope="col" class="name-cell">支払い方法名</th>
            <th scope="col" class="order-cell">表示順</th>
            <th scope="col" class="action-cell">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="paymentMethods.length === 0">
            <td colspan="3" class="empty-cell">支払い方法はまだありません。</td>
          </tr>
          <tr v-for="paymentMethod in paymentMethods" :key="paymentMethod.id">
            <template v-if="editForms[paymentMethod.id]">
              <td class="name-cell">
                <input
                  v-model="editForms[paymentMethod.id].name"
                  type="text"
                  autocomplete="off"
                  @input="schedulePaymentMethodAutoSave(paymentMethod)"
                  @blur="savePaymentMethod(paymentMethod)"
                >
                <small v-if="editErrors[paymentMethod.id]?.name" class="field-error">
                  {{ editErrors[paymentMethod.id]?.name }}
                </small>
              </td>
              <td class="order-cell">
                <input
                  v-model.number="editForms[paymentMethod.id].displayOrder"
                  type="number"
                  min="0"
                  @input="schedulePaymentMethodAutoSave(paymentMethod)"
                  @blur="savePaymentMethod(paymentMethod)"
                >
                <small v-if="editErrors[paymentMethod.id]?.displayOrder" class="field-error">
                  {{ editErrors[paymentMethod.id]?.displayOrder }}
                </small>
              </td>
              <td class="action-cell">
                <p v-if="rowErrors[paymentMethod.id]" class="message error">
                  {{ rowErrors[paymentMethod.id] }}
                </p>
                <div class="row-actions">
                  <button
                    type="button"
                    class="danger-button"
                    :disabled="isSaving(paymentMethod.id) || isDeleting(paymentMethod.id)"
                    @click="confirmDelete(paymentMethod)"
                  >
                    {{ isDeleting(paymentMethod.id) ? '削除中...' : '削除' }}
                  </button>
                </div>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section v-if="!loading && !hasPaymentMethods" class="status-card">
    <p>支払い方法が未登録です。上のフォームから登録してください。</p>
  </section>
</template>
