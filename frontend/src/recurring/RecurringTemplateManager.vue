<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ApiError, toMessage } from '@/api/http'
import {
  createRecurringTransactionTemplate,
  deleteRecurringTransactionTemplate,
  updateRecurringTransactionTemplate,
  type Category,
  type PaymentMethod,
  type RecurringTransactionTemplate,
  type TransferAccount,
} from '@/api/kakeibo'
import { nextDisplayOrderOf } from '@/masters'
import {
  applyTypeDefaults,
  categoriesForType,
  createEmptyTemplateForm,
  primaryLabel,
  resetTemplateForm,
  secondaryLabel,
  toTemplateForm,
  toTemplateRequest,
  validateTemplate,
  type RecurringMasterData,
  type TemplateErrors,
  type TemplateField,
  type TemplateForm,
} from '@/recurring/formModel'

const props = defineProps<{
  initialTemplates: RecurringTransactionTemplate[]
  categories: Category[]
  paymentMethods: PaymentMethod[]
  transferAccounts: TransferAccount[]
  loading: boolean
}>()

const emit = defineEmits<{
  announce: [message: string]
  templateDeleted: []
}>()

const templates = ref<RecurringTransactionTemplate[]>([])
const editForms = reactive<Record<number, TemplateForm>>({})
const editErrors = reactive<Record<number, TemplateErrors>>({})
const rowErrors = reactive<Record<number, string>>({})
const createForm = reactive<TemplateForm>(createEmptyTemplateForm())
const createErrors = reactive<TemplateErrors>({})
const creating = ref(false)
const updatingIds = ref<Set<number>>(new Set())
const deletingIds = ref<Set<number>>(new Set())
const templateError = ref<string | null>(null)

const masters = computed<RecurringMasterData>(() => ({
  categories: props.categories,
  paymentMethods: props.paymentMethods,
  transferAccounts: props.transferAccounts,
}))

watch(
  () =>
    [
      props.initialTemplates,
      props.categories,
      props.paymentMethods,
      props.transferAccounts,
    ] as const,
  ([items]) => {
    setTemplates(items)
    resetCreateForm()
  },
  { immediate: true },
)

function setTemplates(items: readonly RecurringTransactionTemplate[]): void {
  templates.value = [...items].sort(compareTemplates)
  clearObject(editForms)
  clearObject(editErrors)
  clearObject(rowErrors)
  templates.value.forEach((template) => {
    editForms[template.id] = toTemplateForm(template)
  })
}

function upsertTemplate(template: RecurringTransactionTemplate): void {
  templates.value = [
    ...templates.value.filter((current) => current.id !== template.id),
    template,
  ].sort(compareTemplates)
  editForms[template.id] = toTemplateForm(template)
  editErrors[template.id] = {}
  delete rowErrors[template.id]
}

function removeTemplate(id: number): void {
  templates.value = templates.value.filter((template) => template.id !== id)
  delete editForms[id]
  delete editErrors[id]
  delete rowErrors[id]
}

function compareTemplates(
  left: RecurringTransactionTemplate,
  right: RecurringTransactionTemplate,
): number {
  const displayOrder = left.displayOrder - right.displayOrder
  return displayOrder !== 0 ? displayOrder : left.id - right.id
}

function resetCreateForm(): void {
  resetTemplateForm(createForm, masters.value, nextDisplayOrderOf(templates.value))
  clearObject(createErrors)
}

async function submitCreate(): Promise<void> {
  clearObject(createErrors)
  templateError.value = null
  const errors = validateTemplate(createForm, masters.value)
  if (Object.keys(errors).length > 0) {
    Object.assign(createErrors, errors)
    templateError.value = '入力内容に誤りがあります'
    return
  }
  if (creating.value) {
    return
  }

  creating.value = true
  try {
    const created = await createRecurringTransactionTemplate(toTemplateRequest(createForm))
    upsertTemplate(created)
    resetCreateForm()
    emit('announce', `定期取引テンプレート「${created.name}」を登録しました`)
  } catch (error) {
    applyTemplateApiErrors(error, createErrors)
    templateError.value = toMessage(error)
  } finally {
    creating.value = false
  }
}

async function updateTemplate(template: RecurringTransactionTemplate): Promise<void> {
  const form = editForms[template.id]
  if (!form || isBusy(template.id)) {
    return
  }
  editErrors[template.id] = {}
  delete rowErrors[template.id]
  const errors = validateTemplate(form, masters.value)
  if (Object.keys(errors).length > 0) {
    editErrors[template.id] = errors
    rowErrors[template.id] = '入力内容に誤りがあります'
    return
  }

  addId(updatingIds, template.id)
  try {
    const updated = await updateRecurringTransactionTemplate(template.id, toTemplateRequest(form))
    upsertTemplate(updated)
    emit('announce', `定期取引テンプレート「${updated.name}」を更新しました`)
  } catch (error) {
    applyTemplateApiErrors(error, editErrors[template.id])
    rowErrors[template.id] = toMessage(error)
  } finally {
    removeId(updatingIds, template.id)
  }
}

async function confirmDeleteTemplate(template: RecurringTransactionTemplate): Promise<void> {
  if (
    isBusy(template.id) ||
    !window.confirm(`定期取引テンプレート「${template.name}」を削除しますか？`)
  ) {
    return
  }

  addId(deletingIds, template.id)
  delete rowErrors[template.id]
  try {
    await deleteRecurringTransactionTemplate(template.id)
    removeTemplate(template.id)
    resetCreateForm()
    emit('templateDeleted')
    emit('announce', `定期取引テンプレート「${template.name}」を削除しました`)
  } catch (error) {
    rowErrors[template.id] = toMessage(error)
  } finally {
    removeId(deletingIds, template.id)
  }
}

function changeTemplateType(form: TemplateForm): void {
  applyTypeDefaults(form, masters.value)
}

function isBusy(id: number): boolean {
  return updatingIds.value.has(id) || deletingIds.value.has(id)
}

function applyTemplateApiErrors(error: unknown, errors: TemplateErrors): void {
  if (!(error instanceof ApiError)) {
    return
  }
  error.errors.forEach((fieldError) => {
    if (fieldError.field in createForm) {
      errors[fieldError.field as TemplateField] = fieldError.message
    }
  })
}

function clearObject<T extends object>(target: T): void {
  Object.keys(target).forEach((key) => delete target[key as keyof T])
}

function addId(target: typeof updatingIds, id: number): void {
  target.value = new Set([...target.value, id])
}

function removeId(target: typeof updatingIds, id: number): void {
  const next = new Set(target.value)
  next.delete(id)
  target.value = next
}
</script>

<template>
  <section class="category-section" aria-labelledby="recurring-create-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">テンプレート管理</p>
        <h2 id="recurring-create-heading">テンプレートを追加</h2>
      </div>
    </div>

    <form class="recurring-template-form" @submit.prevent="submitCreate">
      <label class="field">
        <span>テンプレート名</span>
        <input v-model="createForm.name" type="text" maxlength="100" autocomplete="off" />
        <small v-if="createErrors.name" class="field-error">{{ createErrors.name }}</small>
      </label>
      <label class="checkbox-field recurring-enabled-field">
        <input v-model="createForm.enabled" type="checkbox" />
        <span>有効</span>
      </label>
      <label class="field">
        <span>毎月の日</span>
        <input
          v-model.number="createForm.dayOfMonth"
          type="number"
          min="1"
          max="31"
          inputmode="numeric"
        />
        <small v-if="createErrors.dayOfMonth" class="field-error">{{
          createErrors.dayOfMonth
        }}</small>
      </label>
      <label class="field">
        <span>種別</span>
        <select v-model="createForm.type" @change="changeTemplateType(createForm)">
          <option value="EXPENSE">支出</option>
          <option value="INCOME">収入</option>
          <option value="TRANSFER">振替</option>
        </select>
      </label>
      <label v-if="createForm.type === 'TRANSFER'" class="field">
        <span>振替元</span>
        <select v-model="createForm.transferSourceId">
          <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
            {{ account.name }}
          </option>
        </select>
        <small v-if="createErrors.transferSourceId" class="field-error">{{
          createErrors.transferSourceId
        }}</small>
      </label>
      <label v-else class="field">
        <span>カテゴリ</span>
        <select v-model="createForm.categoryId">
          <option
            v-for="category in categoriesForType(createForm.type, masters)"
            :key="category.id"
            :value="category.id"
          >
            {{ category.name }}
          </option>
        </select>
        <small v-if="createErrors.categoryId" class="field-error">{{
          createErrors.categoryId
        }}</small>
      </label>
      <label v-if="createForm.type === 'TRANSFER'" class="field">
        <span>振替先</span>
        <select v-model="createForm.transferDestinationId">
          <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
            {{ account.name }}
          </option>
        </select>
        <small v-if="createErrors.transferDestinationId" class="field-error">{{
          createErrors.transferDestinationId
        }}</small>
      </label>
      <label v-else-if="createForm.type === 'EXPENSE'" class="field">
        <span>支払い方法</span>
        <select v-model="createForm.paymentMethodId">
          <option v-for="method in paymentMethods" :key="method.id" :value="method.id">
            {{ method.name }}
          </option>
        </select>
        <small v-if="createErrors.paymentMethodId" class="field-error">{{
          createErrors.paymentMethodId
        }}</small>
      </label>
      <div v-else class="field not-applicable-field">
        <span>支払い方法</span>
        <span class="not-applicable">対象なし</span>
      </div>
      <label class="field">
        <span>標準金額</span>
        <input
          v-model.number="createForm.defaultAmount"
          type="number"
          min="1"
          inputmode="numeric"
          aria-label="標準金額"
        />
        <small>金額が毎月変わる場合は空欄にできます。</small>
        <small v-if="createErrors.defaultAmount" class="field-error">{{
          createErrors.defaultAmount
        }}</small>
      </label>
      <label class="field recurring-memo-field">
        <span>メモ</span>
        <textarea v-model="createForm.memo" maxlength="500" wrap="soft"></textarea>
        <small v-if="createErrors.memo" class="field-error">{{ createErrors.memo }}</small>
      </label>
      <label class="field">
        <span>表示順</span>
        <input v-model.number="createForm.displayOrder" type="number" min="0" inputmode="numeric" />
        <small v-if="createErrors.displayOrder" class="field-error">{{
          createErrors.displayOrder
        }}</small>
      </label>
      <div class="form-actions">
        <button type="submit" :disabled="loading || creating">
          {{ creating ? '登録中...' : '登録' }}
        </button>
      </div>
    </form>
    <p class="summary-note">29日から31日は、その日がない月では月末日に丸められます。</p>
    <p v-if="templateError" class="message error">{{ templateError }}</p>
  </section>

  <section class="category-section" aria-labelledby="recurring-template-list-heading">
    <div class="section-heading">
      <div>
        <p class="eyebrow">テンプレート管理</p>
        <h2 id="recurring-template-list-heading">テンプレート一覧</h2>
      </div>
      <span class="count-badge">{{ templates.length }}件</span>
    </div>

    <div class="table-wrap">
      <table class="category-table recurring-template-table">
        <thead>
          <tr>
            <th scope="col">テンプレート名</th>
            <th scope="col">有効</th>
            <th scope="col">毎月の日</th>
            <th scope="col">種別</th>
            <th scope="col">選択項目1</th>
            <th scope="col">選択項目2</th>
            <th scope="col">標準金額</th>
            <th scope="col">メモ</th>
            <th scope="col">表示順</th>
            <th scope="col" class="action-cell">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="templates.length === 0">
            <td colspan="10" class="empty-cell">テンプレートはまだありません。</td>
          </tr>
          <tr v-for="template in templates" :key="template.id">
            <template v-if="editForms[template.id]">
              <td>
                <span class="sr-only">{{ template.name }}</span>
                <input
                  v-model="editForms[template.id].name"
                  type="text"
                  maxlength="100"
                  autocomplete="off"
                  :aria-label="`${template.name}のテンプレート名`"
                  :disabled="isBusy(template.id)"
                />
                <small v-if="editErrors[template.id]?.name" class="field-error">{{
                  editErrors[template.id]?.name
                }}</small>
              </td>
              <td>
                <input
                  v-model="editForms[template.id].enabled"
                  type="checkbox"
                  :aria-label="`${template.name}を有効にする`"
                  :disabled="isBusy(template.id)"
                />
              </td>
              <td>
                <input
                  v-model.number="editForms[template.id].dayOfMonth"
                  type="number"
                  min="1"
                  max="31"
                  inputmode="numeric"
                  :aria-label="`${template.name}の毎月の日`"
                  :disabled="isBusy(template.id)"
                />
                <small v-if="editErrors[template.id]?.dayOfMonth" class="field-error">{{
                  editErrors[template.id]?.dayOfMonth
                }}</small>
              </td>
              <td>
                <select
                  v-model="editForms[template.id].type"
                  :aria-label="`${template.name}の種別`"
                  :disabled="isBusy(template.id)"
                  @change="changeTemplateType(editForms[template.id])"
                >
                  <option value="EXPENSE">支出</option>
                  <option value="INCOME">収入</option>
                  <option value="TRANSFER">振替</option>
                </select>
              </td>
              <td>
                <span class="recurring-cell-label">{{
                  primaryLabel(editForms[template.id].type)
                }}</span>
                <select
                  v-if="editForms[template.id].type === 'TRANSFER'"
                  v-model="editForms[template.id].transferSourceId"
                  :aria-label="`${template.name}の振替元`"
                  :disabled="isBusy(template.id)"
                >
                  <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
                    {{ account.name }}
                  </option>
                </select>
                <select
                  v-else
                  v-model="editForms[template.id].categoryId"
                  :aria-label="`${template.name}のカテゴリ`"
                  :disabled="isBusy(template.id)"
                >
                  <option
                    v-for="category in categoriesForType(editForms[template.id].type, masters)"
                    :key="category.id"
                    :value="category.id"
                  >
                    {{ category.name }}
                  </option>
                </select>
                <small
                  v-if="
                    editErrors[template.id]?.transferSourceId || editErrors[template.id]?.categoryId
                  "
                  class="field-error"
                  >{{
                    editErrors[template.id]?.transferSourceId ?? editErrors[template.id]?.categoryId
                  }}</small
                >
              </td>
              <td>
                <span class="recurring-cell-label">{{
                  secondaryLabel(editForms[template.id].type)
                }}</span>
                <span v-if="editForms[template.id].type === 'INCOME'" class="not-applicable"
                  >対象なし</span
                >
                <select
                  v-else-if="editForms[template.id].type === 'TRANSFER'"
                  v-model="editForms[template.id].transferDestinationId"
                  :aria-label="`${template.name}の振替先`"
                  :disabled="isBusy(template.id)"
                >
                  <option v-for="account in transferAccounts" :key="account.id" :value="account.id">
                    {{ account.name }}
                  </option>
                </select>
                <select
                  v-else
                  v-model="editForms[template.id].paymentMethodId"
                  :aria-label="`${template.name}の支払い方法`"
                  :disabled="isBusy(template.id)"
                >
                  <option v-for="method in paymentMethods" :key="method.id" :value="method.id">
                    {{ method.name }}
                  </option>
                </select>
                <small
                  v-if="
                    editErrors[template.id]?.transferDestinationId ||
                    editErrors[template.id]?.paymentMethodId
                  "
                  class="field-error"
                  >{{
                    editErrors[template.id]?.transferDestinationId ??
                    editErrors[template.id]?.paymentMethodId
                  }}</small
                >
              </td>
              <td>
                <input
                  v-model.number="editForms[template.id].defaultAmount"
                  type="number"
                  min="1"
                  inputmode="numeric"
                  :aria-label="`${template.name}の標準金額`"
                  :disabled="isBusy(template.id)"
                />
                <small v-if="editErrors[template.id]?.defaultAmount" class="field-error">{{
                  editErrors[template.id]?.defaultAmount
                }}</small>
              </td>
              <td>
                <textarea
                  v-model="editForms[template.id].memo"
                  maxlength="500"
                  wrap="soft"
                  :aria-label="`${template.name}のメモ`"
                  :disabled="isBusy(template.id)"
                ></textarea>
                <small v-if="editErrors[template.id]?.memo" class="field-error">{{
                  editErrors[template.id]?.memo
                }}</small>
              </td>
              <td>
                <input
                  v-model.number="editForms[template.id].displayOrder"
                  type="number"
                  min="0"
                  inputmode="numeric"
                  :aria-label="`${template.name}の表示順`"
                  :disabled="isBusy(template.id)"
                />
                <small v-if="editErrors[template.id]?.displayOrder" class="field-error">{{
                  editErrors[template.id]?.displayOrder
                }}</small>
              </td>
              <td class="action-cell">
                <p v-if="rowErrors[template.id]" class="message error">
                  {{ rowErrors[template.id] }}
                </p>
                <div class="row-actions recurring-template-actions">
                  <button
                    type="button"
                    :disabled="isBusy(template.id)"
                    @click="updateTemplate(template)"
                  >
                    {{ updatingIds.has(template.id) ? '更新中...' : '更新' }}
                  </button>
                  <button
                    type="button"
                    class="danger-button"
                    :disabled="isBusy(template.id)"
                    @click="confirmDeleteTemplate(template)"
                  >
                    {{ deletingIds.has(template.id) ? '削除中...' : '削除' }}
                  </button>
                </div>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
