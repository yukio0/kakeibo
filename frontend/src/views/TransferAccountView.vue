<script setup lang="ts">
import {
  createTransferAccount,
  deleteTransferAccount,
  getTransferAccounts,
  type TransferAccount,
  type TransferAccountRequest,
  updateTransferAccount,
} from '@/api/kakeibo'
import { useMasterCrud } from '@/composables/useMasterCrud'
import { compareByDisplayOrder, nextDisplayOrderOf, type MasterForm } from '@/masters'

const {
  items: transferAccounts,
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
  isSaving,
  isDeleting,
} = useMasterCrud<TransferAccount, MasterForm, TransferAccountRequest>({
  list: getTransferAccounts,
  create: createTransferAccount,
  update: updateTransferAccount,
  remove: deleteTransferAccount,
  entityLabel: '振替元・振替先',
  minimumRequiredMessage: '振替元・振替先は最低1件必要です',
  canDelete: (items) => items.length > 1,
  fields: ['name', 'displayOrder'],
  emptyForm: () => ({ name: '', displayOrder: 0 }),
  toForm: (transferAccount) => ({
    name: transferAccount.name,
    displayOrder: transferAccount.displayOrder,
  }),
  toRequest: (form) => ({ name: form.name.trim(), displayOrder: Number(form.displayOrder) }),
  isSameRequest: (form, request) =>
    form.name.trim() === request.name && Object.is(Number(form.displayOrder), request.displayOrder),
  compare: compareByDisplayOrder,
  nextDisplayOrder: (items) => nextDisplayOrderOf(items),
})
</script>

<template>
  <section class="page-heading">
    <h1>振替管理</h1>
    <p>銀行口座や財布など、振替元と振替先に使う資産を登録、編集、削除できます。</p>
  </section>

  <section class="status-card">
    <h2>振替元・振替先を追加</h2>
    <form class="form-grid form-grid-compact" @submit.prevent="submitCreate">
      <label class="field">
        <span>資産名</span>
        <input v-model="createForm.name" type="text" autocomplete="off" />
        <small v-if="createErrors.name" class="field-error">{{ createErrors.name }}</small>
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
                  @input="scheduleAutoSave(transferAccount)"
                  @blur="save(transferAccount)"
                />
                <small v-if="editErrors[transferAccount.id]?.name" class="field-error">
                  {{ editErrors[transferAccount.id]?.name }}
                </small>
              </td>
              <td class="order-cell">
                <input
                  v-model.number="editForms[transferAccount.id].displayOrder"
                  type="number"
                  min="0"
                  @input="scheduleAutoSave(transferAccount)"
                  @blur="save(transferAccount)"
                />
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

  <section v-if="!loading && !hasItems" class="status-card">
    <p>振替元・振替先が未登録です。上のフォームから登録してください。</p>
  </section>
</template>
