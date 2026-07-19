<script setup lang="ts">
import { computed, watch } from 'vue'
import {
  createCategory,
  deleteCategory,
  getCategories,
  type Category,
  type CategoryRequest,
  type CategoryType,
  updateCategory,
} from '@/api/kakeibo'
import { useMasterCrud } from '@/composables/useMasterCrud'
import { compareCategories, nextDisplayOrderOf } from '@/masters'

type CategoryForm = {
  name: string
  type: CategoryType
  displayOrder: number
}

const typeLabels: Record<CategoryType, string> = {
  EXPENSE: '支出',
  INCOME: '収入',
}

const {
  items: categories,
  loading,
  creating,
  listError,
  successMessage,
  createForm,
  createErrors,
  editForms,
  editErrors,
  rowErrors,
  hasItems,
  submitCreate,
  save,
  scheduleAutoSave,
  confirmDelete,
  refreshCreateDisplayOrder,
  isSaving,
  isDeleting,
} = useMasterCrud<Category, CategoryForm, CategoryRequest>({
  list: getCategories,
  create: createCategory,
  update: updateCategory,
  remove: deleteCategory,
  entityLabel: 'カテゴリ',
  minimumRequiredMessage: '各種別のカテゴリは最低1件必要です',
  canDelete: (items, item) => items.filter((current) => current.type === item.type).length > 1,
  fields: ['name', 'type', 'displayOrder'],
  emptyForm: () => ({ name: '', type: 'EXPENSE', displayOrder: 0 }),
  toForm: (category) => ({
    name: category.name,
    type: category.type,
    displayOrder: category.displayOrder,
  }),
  toRequest: (form) => ({
    name: form.name.trim(),
    type: form.type,
    displayOrder: Number(form.displayOrder),
  }),
  isSameRequest: (form, request) =>
    form.name.trim() === request.name &&
    form.type === request.type &&
    Object.is(Number(form.displayOrder), request.displayOrder),
  compare: compareCategories,
  nextDisplayOrder: (items, form) =>
    nextDisplayOrderOf(items.filter((category) => category.type === form.type)),
})

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

watch(() => createForm.type, refreshCreateDisplayOrder)
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

    <form class="form-grid category-create-form" @submit.prevent="submitCreate">
      <label class="field">
        <span>カテゴリ名</span>
        <input v-model="createForm.name" type="text" autocomplete="off" />
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
        <input v-model.number="createForm.displayOrder" type="number" min="0" />
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
                  @input="scheduleAutoSave(category)"
                  @blur="save(category)"
                />
                <small v-if="editErrors[category.id]?.name" class="field-error">
                  {{ editErrors[category.id]?.name }}
                </small>
              </td>
              <td class="type-cell">
                <select
                  v-model="editForms[category.id].type"
                  @change="scheduleAutoSave(category)"
                  @blur="save(category)"
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
                  @input="scheduleAutoSave(category)"
                  @blur="save(category)"
                />
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

  <section v-if="!loading && !hasItems" class="status-card">
    <p>カテゴリが未登録です。上のフォームから登録してください。</p>
  </section>
</template>
