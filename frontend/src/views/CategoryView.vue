<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ApiError } from '@/api/http'
import {
  createCategory,
  deleteCategory,
  getCategories,
  type Category,
  type CategoryRequest,
  type TransactionType,
  updateCategory,
} from '@/api/kakeibo'

type CategoryForm = {
  name: string
  type: CategoryType
  displayOrder: number
}

type CategoryType = Exclude<TransactionType, 'TRANSFER'>
type CategoryField = keyof CategoryForm
type FieldErrors = Partial<Record<CategoryField, string>>

const typeLabels: Record<CategoryType, string> = {
  EXPENSE: '支出',
  INCOME: '収入',
}

const AUTO_SAVE_DELAY_MS = 700

const categories = ref<Category[]>([])
const loading = ref(false)
const creating = ref(false)
const savingIds = ref<number[]>([])
const deletingIds = ref<number[]>([])
const listError = ref<string | null>(null)
const successMessage = ref<string | null>(null)

const createForm = reactive<CategoryForm>({
  name: '',
  type: 'EXPENSE',
  displayOrder: 0,
})
const createErrors = ref<FieldErrors>({})
const editForms = reactive<Record<number, CategoryForm>>({})
const editErrors = reactive<Record<number, FieldErrors>>({})
const rowErrors = reactive<Record<number, string>>({})
const autoSaveTimers = new Map<number, number>()

const categoryGroups = computed(() => [
  {
    type: 'EXPENSE' as const,
    title: '支出カテゴリ',
    categories: categories.value.filter((category) => category.type === 'EXPENSE'),
  },
  {
    type: 'INCOME' as const,
    title: '収入カテゴリ',
    categories: categories.value.filter((category) => category.type === 'INCOME'),
  },
])

const hasCategories = computed(() => categories.value.length > 0)

watch(
  () => createForm.type,
  () => {
    createForm.displayOrder = nextDisplayOrder(createForm.type)
  },
)

onMounted(() => {
  void loadCategories()
})

onBeforeUnmount(() => {
  autoSaveTimers.forEach((timer) => window.clearTimeout(timer))
  autoSaveTimers.clear()
})

async function loadCategories(): Promise<void> {
  loading.value = true
  listError.value = null

  try {
    categories.value = sortCategories(await getCategories())
    syncEditForms()
    createForm.displayOrder = nextDisplayOrder(createForm.type)
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
    const created = await createCategory(toRequest(createForm))
    categories.value = sortCategories([...categories.value, created])
    syncEditForms()
    createForm.name = ''
    createForm.displayOrder = nextDisplayOrder(createForm.type)
    successMessage.value = 'カテゴリを登録しました'
  } catch (error) {
    if (error instanceof ApiError) {
      createErrors.value = toFieldErrors(error)
    }
    listError.value = toMessage(error)
  } finally {
    creating.value = false
  }
}

async function saveCategory(category: Category): Promise<void> {
  const form = editForms[category.id]
  if (!form || isDeleting(category.id)) {
    return
  }

  if (!isDirty(category)) {
    clearCategoryAutoSaveTimer(category.id)
    return
  }

  if (isSaving(category.id)) {
    scheduleCategoryAutoSave(category)
    return
  }

  clearCategoryAutoSaveTimer(category.id)
  const request = toRequest(form)

  setBusy(savingIds, category.id, true)
  editErrors[category.id] = {}
  delete rowErrors[category.id]
  successMessage.value = null

  try {
    const updated = await updateCategory(category.id, request)
    categories.value = sortCategories(
      categories.value.map((current) => (current.id === updated.id ? updated : current)),
    )
    if (isSameRequest(form, request)) {
      editForms[category.id] = toForm(updated)
    } else {
      scheduleCategoryAutoSave(updated)
    }
  } catch (error) {
    if (!isSameRequest(form, request)) {
      scheduleCategoryAutoSave(category)
    } else if (error instanceof ApiError) {
      editErrors[category.id] = toFieldErrors(error)
      rowErrors[category.id] = toMessage(error)
    } else {
      rowErrors[category.id] = toMessage(error)
    }
  } finally {
    setBusy(savingIds, category.id, false)
  }
}

function scheduleCategoryAutoSave(category: Category): void {
  const currentTimer = autoSaveTimers.get(category.id)
  if (currentTimer !== undefined) {
    window.clearTimeout(currentTimer)
  }

  autoSaveTimers.set(
    category.id,
    window.setTimeout(() => {
      autoSaveTimers.delete(category.id)
      void saveCategory(category)
    }, AUTO_SAVE_DELAY_MS),
  )
}

function clearCategoryAutoSaveTimer(id: number): void {
  const timer = autoSaveTimers.get(id)
  if (timer !== undefined) {
    window.clearTimeout(timer)
    autoSaveTimers.delete(id)
  }
}

async function confirmDelete(category: Category): Promise<void> {
  if (categories.value.filter((current) => current.type === category.type).length <= 1) {
    rowErrors[category.id] = '各種別のカテゴリは最低1件必要です'
    successMessage.value = null
    return
  }

  const confirmed = window.confirm(`カテゴリ「${category.name}」を削除します。よろしいですか？`)
  if (!confirmed) {
    return
  }

  setBusy(deletingIds, category.id, true)
  clearCategoryAutoSaveTimer(category.id)
  delete rowErrors[category.id]
  successMessage.value = null

  try {
    await deleteCategory(category.id)
    categories.value = categories.value.filter((current) => current.id !== category.id)
    delete editForms[category.id]
    delete editErrors[category.id]
    clearCategoryAutoSaveTimer(category.id)
    successMessage.value = 'カテゴリを削除しました'
  } catch (error) {
    rowErrors[category.id] = toMessage(error)
  } finally {
    setBusy(deletingIds, category.id, false)
  }
}

function isSaving(id: number): boolean {
  return savingIds.value.includes(id)
}

function isDeleting(id: number): boolean {
  return deletingIds.value.includes(id)
}

function isDirty(category: Category): boolean {
  const form = editForms[category.id]
  return (
    !!form &&
    (form.name !== category.name ||
      form.type !== category.type ||
      form.displayOrder !== category.displayOrder)
  )
}

function nextDisplayOrder(type: CategoryType): number {
  const orders = categories.value
    .filter((category) => category.type === type)
    .map((category) => category.displayOrder)
  return Math.max(-10, ...orders) + 10
}

function syncEditForms(): void {
  categories.value.forEach((category) => {
    editForms[category.id] = toForm(category)
    editErrors[category.id] = editErrors[category.id] ?? {}
  })

  Object.keys(editForms).forEach((id) => {
    const categoryId = Number(id)
    if (!categories.value.some((category) => category.id === categoryId)) {
      delete editForms[categoryId]
      delete editErrors[categoryId]
      delete rowErrors[categoryId]
      clearCategoryAutoSaveTimer(categoryId)
    }
  })
}

function toForm(category: Category): CategoryForm {
  return {
    name: category.name,
    type: category.type,
    displayOrder: category.displayOrder,
  }
}

function toRequest(form: CategoryForm): CategoryRequest {
  return {
    name: form.name.trim(),
    type: form.type,
    displayOrder: Number(form.displayOrder),
  }
}

function isSameRequest(form: CategoryForm, request: CategoryRequest): boolean {
  return (
    form.name.trim() === request.name &&
    form.type === request.type &&
    Object.is(Number(form.displayOrder), request.displayOrder)
  )
}

function toFieldErrors(error: ApiError): FieldErrors {
  const result: FieldErrors = {}
  error.errors.forEach((fieldError) => {
    if (isCategoryField(fieldError.field)) {
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

function isCategoryField(field: string): field is CategoryField {
  return field === 'name' || field === 'type' || field === 'displayOrder'
}

function sortCategories(items: Category[]): Category[] {
  return [...items].sort((left, right) => {
    const typeOrder = left.type.localeCompare(right.type)
    if (typeOrder !== 0) {
      return typeOrder
    }

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
    <h1>カテゴリ管理</h1>
    <p>支出用・収入用カテゴリを一覧表示し、登録、編集、削除できます。</p>
  </section>

  <section class="status-card">
    <div>
      <p class="eyebrow">新規登録</p>
      <h2>カテゴリを追加</h2>
    </div>

    <form class="form-grid" @submit.prevent="submitCreate">
      <label class="field">
        <span>カテゴリ名</span>
        <input v-model="createForm.name" type="text" autocomplete="off">
        <small v-if="createErrors.name" class="field-error">{{ createErrors.name }}</small>
      </label>

      <label class="field">
        <span>種別</span>
        <select v-model="createForm.type">
          <option value="EXPENSE">支出</option>
          <option value="INCOME">収入</option>
        </select>
        <small v-if="createErrors.type" class="field-error">{{ createErrors.type }}</small>
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

  <section
    v-for="group in categoryGroups"
    :key="group.type"
    class="category-section"
    :aria-labelledby="`category-${group.type}`"
  >
    <div class="section-heading">
      <div>
        <p class="eyebrow">{{ typeLabels[group.type] }}</p>
        <h2 :id="`category-${group.type}`">{{ group.title }}</h2>
      </div>
      <span class="count-badge">{{ group.categories.length }}件</span>
    </div>

    <div class="table-wrap">
      <table class="category-table">
        <thead>
          <tr>
            <th scope="col" class="name-cell">カテゴリ名</th>
            <th scope="col" class="type-cell">種別</th>
            <th scope="col" class="order-cell">表示順</th>
            <th scope="col" class="action-cell">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="group.categories.length === 0">
            <td colspan="4" class="empty-cell">カテゴリはまだありません。</td>
          </tr>
          <tr v-for="category in group.categories" :key="category.id">
            <template v-if="editForms[category.id]">
              <td class="name-cell">
                <input
                  v-model="editForms[category.id].name"
                  type="text"
                  autocomplete="off"
                  @input="scheduleCategoryAutoSave(category)"
                  @blur="saveCategory(category)"
                >
                <small v-if="editErrors[category.id]?.name" class="field-error">
                  {{ editErrors[category.id]?.name }}
                </small>
              </td>
              <td class="type-cell">
                <select
                  v-model="editForms[category.id].type"
                  @change="scheduleCategoryAutoSave(category)"
                  @blur="saveCategory(category)"
                >
                  <option value="EXPENSE">支出</option>
                  <option value="INCOME">収入</option>
                </select>
                <small v-if="editErrors[category.id]?.type" class="field-error">
                  {{ editErrors[category.id]?.type }}
                </small>
              </td>
              <td class="order-cell">
                <input
                  v-model.number="editForms[category.id].displayOrder"
                  type="number"
                  min="0"
                  @input="scheduleCategoryAutoSave(category)"
                  @blur="saveCategory(category)"
                >
                <small v-if="editErrors[category.id]?.displayOrder" class="field-error">
                  {{ editErrors[category.id]?.displayOrder }}
                </small>
              </td>
              <td class="action-cell">
                <p v-if="rowErrors[category.id]" class="message error">
                  {{ rowErrors[category.id] }}
                </p>
                <div class="row-actions">
                  <button
                    type="button"
                    class="danger-button"
                    :disabled="isSaving(category.id) || isDeleting(category.id)"
                    @click="confirmDelete(category)"
                  >
                    {{ isDeleting(category.id) ? '削除中...' : '削除' }}
                  </button>
                </div>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section v-if="!loading && !hasCategories" class="status-card">
    <p>カテゴリが未登録です。上のフォームから登録してください。</p>
  </section>
</template>
