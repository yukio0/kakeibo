import { computed, onBeforeUnmount, onMounted, reactive, ref, type Ref } from 'vue'
import { ApiError, toMessage } from '@/api/http'
import type { MasterForm, MasterItem } from '@/masters'

const AUTO_SAVE_DELAY_MS = 700

export type MasterFieldErrors<TForm> = Partial<Record<keyof TForm & string, string>>

export type MasterCrudOptions<TItem extends MasterItem, TForm extends MasterForm, TRequest> = {
  list: () => Promise<TItem[]>
  create: (request: TRequest) => Promise<TItem>
  update: (id: number, request: TRequest) => Promise<TItem>
  remove: (id: number) => Promise<void>
  /** 「◯◯を登録しました」「◯◯を削除します」の◯◯にあたる名詞。 */
  entityLabel: string
  minimumRequiredMessage: string
  canDelete: (items: TItem[], item: TItem) => boolean
  fields: readonly (keyof TForm & string)[]
  emptyForm: () => TForm
  toForm: (item: TItem) => TForm
  toRequest: (form: TForm) => TRequest
  isSameRequest: (form: TForm, request: TRequest) => boolean
  compare: (left: TItem, right: TItem) => number
  nextDisplayOrder: (items: TItem[], form: TForm) => number
}

/**
 * 名前と表示順を編集するマスタ管理画面の共通ロジック。
 *
 * 一覧の読み込み、新規登録、行ごとのデバウンス自動保存、削除、
 * およびフィールド単位・行単位のエラー保持をまとめて扱う。
 */
export function useMasterCrud<TItem extends MasterItem, TForm extends MasterForm, TRequest>(
  options: MasterCrudOptions<TItem, TForm, TRequest>,
) {
  const items = ref([]) as Ref<TItem[]>
  const loading = ref(false)
  const creating = ref(false)
  const savingIds = ref<number[]>([])
  const deletingIds = ref<number[]>([])
  const listError = ref<string | null>(null)
  const successMessage = ref<string | null>(null)

  const createForm = reactive(options.emptyForm()) as TForm
  const createErrors = ref<MasterFieldErrors<TForm>>({})
  const editForms = reactive({}) as Record<number, TForm>
  const editErrors = reactive({}) as Record<number, MasterFieldErrors<TForm>>
  const rowErrors = reactive({}) as Record<number, string>
  const autoSaveTimers = new Map<number, number>()

  const hasItems = computed(() => items.value.length > 0)

  onMounted(() => {
    void load()
  })

  onBeforeUnmount(() => {
    autoSaveTimers.forEach((timer) => window.clearTimeout(timer))
    autoSaveTimers.clear()
  })

  async function load(): Promise<void> {
    loading.value = true
    listError.value = null

    try {
      items.value = sorted(await options.list())
      syncEditForms()
      refreshCreateDisplayOrder()
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
      const created = await options.create(options.toRequest(createForm))
      items.value = sorted([...items.value, created])
      syncEditForms()
      createForm.name = ''
      refreshCreateDisplayOrder()
      successMessage.value = `${options.entityLabel}を登録しました`
    } catch (error) {
      if (error instanceof ApiError) {
        createErrors.value = toFieldErrors(error)
      }
      listError.value = toMessage(error)
    } finally {
      creating.value = false
    }
  }

  async function save(item: TItem): Promise<void> {
    const form = editForms[item.id]
    if (!form || isDeleting(item.id)) {
      return
    }

    if (!isDirty(item)) {
      clearAutoSaveTimer(item.id)
      return
    }

    if (isSaving(item.id)) {
      scheduleAutoSave(item)
      return
    }

    clearAutoSaveTimer(item.id)
    const request = options.toRequest(form)

    setBusy(savingIds, item.id, true)
    editErrors[item.id] = {}
    delete rowErrors[item.id]
    successMessage.value = null

    try {
      const updated = await options.update(item.id, request)
      items.value = sorted(
        items.value.map((current) => (current.id === updated.id ? updated : current)),
      )
      if (options.isSameRequest(form, request)) {
        editForms[item.id] = options.toForm(updated)
      } else {
        scheduleAutoSave(updated)
      }
    } catch (error) {
      if (!options.isSameRequest(form, request)) {
        scheduleAutoSave(item)
      } else if (error instanceof ApiError) {
        editErrors[item.id] = toFieldErrors(error)
        rowErrors[item.id] = toMessage(error)
      } else {
        rowErrors[item.id] = toMessage(error)
      }
    } finally {
      setBusy(savingIds, item.id, false)
    }
  }

  function scheduleAutoSave(item: TItem): void {
    clearAutoSaveTimer(item.id)

    autoSaveTimers.set(
      item.id,
      window.setTimeout(() => {
        autoSaveTimers.delete(item.id)
        void save(item)
      }, AUTO_SAVE_DELAY_MS),
    )
  }

  function clearAutoSaveTimer(id: number): void {
    const timer = autoSaveTimers.get(id)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      autoSaveTimers.delete(id)
    }
  }

  async function confirmDelete(item: TItem): Promise<void> {
    if (!options.canDelete(items.value, item)) {
      rowErrors[item.id] = options.minimumRequiredMessage
      successMessage.value = null
      return
    }

    const confirmed = window.confirm(
      `${options.entityLabel}「${item.name}」を削除します。よろしいですか？`,
    )
    if (!confirmed) {
      return
    }

    setBusy(deletingIds, item.id, true)
    clearAutoSaveTimer(item.id)
    delete rowErrors[item.id]
    successMessage.value = null

    try {
      await options.remove(item.id)
      items.value = items.value.filter((current) => current.id !== item.id)
      delete editForms[item.id]
      delete editErrors[item.id]
      clearAutoSaveTimer(item.id)
      successMessage.value = `${options.entityLabel}を削除しました`
    } catch (error) {
      rowErrors[item.id] = toMessage(error)
    } finally {
      setBusy(deletingIds, item.id, false)
    }
  }

  function refreshCreateDisplayOrder(): void {
    createForm.displayOrder = options.nextDisplayOrder(items.value, createForm)
  }

  function isSaving(id: number): boolean {
    return savingIds.value.includes(id)
  }

  function isDeleting(id: number): boolean {
    return deletingIds.value.includes(id)
  }

  function isDirty(item: TItem): boolean {
    const form = editForms[item.id]
    if (!form) {
      return false
    }

    const persisted = options.toForm(item)
    return options.fields.some((field) => form[field] !== persisted[field])
  }

  function syncEditForms(): void {
    items.value.forEach((item) => {
      editForms[item.id] = options.toForm(item)
      editErrors[item.id] = editErrors[item.id] ?? {}
    })

    Object.keys(editForms).forEach((key) => {
      const id = Number(key)
      if (!items.value.some((item) => item.id === id)) {
        delete editForms[id]
        delete editErrors[id]
        delete rowErrors[id]
        clearAutoSaveTimer(id)
      }
    })
  }

  function sorted(target: TItem[]): TItem[] {
    return [...target].sort(options.compare)
  }

  function toFieldErrors(error: ApiError): MasterFieldErrors<TForm> {
    const result: MasterFieldErrors<TForm> = {}
    error.errors.forEach((fieldError) => {
      const field = options.fields.find((candidate) => candidate === fieldError.field)
      if (field) {
        result[field] = fieldError.message
      }
    })
    return result
  }

  function setBusy(target: Ref<number[]>, id: number, busy: boolean): void {
    target.value = busy
      ? Array.from(new Set([...target.value, id]))
      : target.value.filter((current) => current !== id)
  }

  return {
    items,
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
    load,
    submitCreate,
    save,
    scheduleAutoSave,
    confirmDelete,
    refreshCreateDisplayOrder,
    isSaving,
    isDeleting,
  }
}
