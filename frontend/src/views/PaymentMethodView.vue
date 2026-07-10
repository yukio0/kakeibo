<script setup lang="ts">
import {
  createPaymentMethod,
  deletePaymentMethod,
  getPaymentMethods,
  type PaymentMethod,
  type PaymentMethodRequest,
  updatePaymentMethod,
} from '@/api/kakeibo'
import { useMasterCrud } from '@/composables/useMasterCrud'
import { compareByDisplayOrder, nextDisplayOrderOf, type MasterForm } from '@/masters'

const {
  items: paymentMethods,
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
} = useMasterCrud<PaymentMethod, MasterForm, PaymentMethodRequest>({
  list: getPaymentMethods,
  create: createPaymentMethod,
  update: updatePaymentMethod,
  remove: deletePaymentMethod,
  entityLabel: '支払い方法',
  minimumRequiredMessage: '支払い方法は最低1件必要です',
  canDelete: (items) => items.length > 1,
  fields: ['name', 'displayOrder'],
  emptyForm: () => ({ name: '', displayOrder: 0 }),
  toForm: (paymentMethod) => ({
    name: paymentMethod.name,
    displayOrder: paymentMethod.displayOrder,
  }),
  toRequest: (form) => ({ name: form.name.trim(), displayOrder: Number(form.displayOrder) }),
  isSameRequest: (form, request) =>
    form.name.trim() === request.name &&
    Object.is(Number(form.displayOrder), request.displayOrder),
  compare: compareByDisplayOrder,
  nextDisplayOrder: (items) => nextDisplayOrderOf(items),
})
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

    <form class="form-grid form-grid-compact" @submit.prevent="submitCreate">
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
                  @input="scheduleAutoSave(paymentMethod)"
                  @blur="save(paymentMethod)"
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
                  @input="scheduleAutoSave(paymentMethod)"
                  @blur="save(paymentMethod)"
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

  <section v-if="!loading && !hasItems" class="status-card">
    <p>支払い方法が未登録です。上のフォームから登録してください。</p>
  </section>
</template>
