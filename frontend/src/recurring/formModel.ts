import type {
  Category,
  PaymentMethod,
  RecurringTransactionCandidate,
  RecurringTransactionRegistrationItem,
  RecurringTransactionTemplate,
  RecurringTransactionTemplateRequest,
  TransactionType,
  TransferAccount,
} from '@/api/kakeibo'

export type OptionalNumber = number | ''

export type RecurringMasterData = {
  categories: readonly Category[]
  paymentMethods: readonly PaymentMethod[]
  transferAccounts: readonly TransferAccount[]
}

export type TargetForm = {
  type: TransactionType
  categoryId: OptionalNumber
  paymentMethodId: OptionalNumber
  transferSourceId: OptionalNumber
  transferDestinationId: OptionalNumber
}

export type TargetField =
  | 'categoryId'
  | 'paymentMethodId'
  | 'transferSourceId'
  | 'transferDestinationId'

export type TargetErrors = Partial<Record<TargetField, string>>

export type TemplateForm = TargetForm & {
  name: string
  enabled: boolean
  dayOfMonth: OptionalNumber
  defaultAmount: OptionalNumber
  memo: string
  displayOrder: OptionalNumber
}

export type TemplateField = keyof TemplateForm
export type TemplateErrors = Partial<Record<TemplateField, string>>

export type CandidateDraft = Omit<
  RecurringTransactionCandidate,
  | 'categoryId'
  | 'paymentMethodId'
  | 'transferSourceId'
  | 'transferDestinationId'
  | 'amount'
  | 'memo'
> &
  TargetForm & {
    selected: boolean
    amount: OptionalNumber
    memo: string
  }

export type CandidateField = 'date' | 'type' | TargetField | 'amount' | 'memo'
export type CandidateErrors = Partial<Record<CandidateField, string>>

export function createEmptyTemplateForm(): TemplateForm {
  return {
    name: '',
    enabled: true,
    dayOfMonth: 1,
    type: 'EXPENSE',
    categoryId: '',
    paymentMethodId: '',
    transferSourceId: '',
    transferDestinationId: '',
    defaultAmount: '',
    memo: '',
    displayOrder: 0,
  }
}

export function resetTemplateForm(
  form: TemplateForm,
  masters: RecurringMasterData,
  displayOrder: number,
): void {
  Object.assign(form, createEmptyTemplateForm(), { displayOrder })
  applyTypeDefaults(form, masters)
}

export function toTemplateForm(template: RecurringTransactionTemplate): TemplateForm {
  return {
    name: template.name,
    enabled: template.enabled,
    dayOfMonth: template.dayOfMonth,
    type: template.type,
    categoryId: template.categoryId ?? '',
    paymentMethodId: template.paymentMethodId ?? '',
    transferSourceId: template.transferSourceId ?? '',
    transferDestinationId: template.transferDestinationId ?? '',
    defaultAmount: template.defaultAmount ?? '',
    memo: template.memo ?? '',
    displayOrder: template.displayOrder,
  }
}

export function toTemplateRequest(form: TemplateForm): RecurringTransactionTemplateRequest {
  return {
    name: form.name.trim(),
    enabled: form.enabled,
    dayOfMonth: Number(form.dayOfMonth),
    type: form.type,
    categoryId: form.type === 'TRANSFER' ? null : toNullableNumber(form.categoryId),
    paymentMethodId: form.type === 'EXPENSE' ? toNullableNumber(form.paymentMethodId) : null,
    transferSourceId: form.type === 'TRANSFER' ? toNullableNumber(form.transferSourceId) : null,
    transferDestinationId:
      form.type === 'TRANSFER' ? toNullableNumber(form.transferDestinationId) : null,
    defaultAmount: toNullableNumber(form.defaultAmount),
    memo: form.memo.trim() === '' ? null : form.memo.trim(),
    displayOrder: Number(form.displayOrder),
  }
}

export function validateTemplate(form: TemplateForm, masters: RecurringMasterData): TemplateErrors {
  const errors: TemplateErrors = { ...validateTargets(form, masters) }
  if (form.name.trim() === '') {
    errors.name = 'テンプレート名を入力してください'
  } else if (form.name.trim().length > 100) {
    errors.name = 'テンプレート名は100文字以内で入力してください'
  }
  if (!isIntegerInRange(form.dayOfMonth, 1, 31)) {
    errors.dayOfMonth = '毎月の日は1から31の整数で入力してください'
  }
  if (!isIntegerInRange(form.displayOrder, 0)) {
    errors.displayOrder = '表示順は0以上の整数で入力してください'
  }
  if (form.defaultAmount !== '' && !isIntegerInRange(form.defaultAmount, 1)) {
    errors.defaultAmount = '標準金額は1以上の整数で入力してください'
  }
  if (form.memo.length > 500) {
    errors.memo = 'メモは500文字以内で入力してください'
  }
  return errors
}

export function toCandidateDraft(candidate: RecurringTransactionCandidate): CandidateDraft {
  return {
    ...candidate,
    selected: !candidate.registered,
    categoryId: candidate.categoryId ?? '',
    paymentMethodId: candidate.paymentMethodId ?? '',
    transferSourceId: candidate.transferSourceId ?? '',
    transferDestinationId: candidate.transferDestinationId ?? '',
    amount: candidate.amount ?? '',
    memo: candidate.memo ?? '',
  }
}

export function validateCandidate(
  candidate: CandidateDraft,
  masters: RecurringMasterData,
  monthStartDate: string,
  monthEndDate: string,
): CandidateErrors {
  const errors: CandidateErrors = { ...validateTargets(candidate, masters) }
  if (!isDateInSelectedMonth(candidate.date, monthStartDate, monthEndDate)) {
    errors.date = '対象月の日付を入力してください'
  }
  if (!isIntegerInRange(candidate.amount, 1)) {
    errors.amount = '金額を1以上の整数で入力してください'
  }
  if (candidate.memo.length > 500) {
    errors.memo = 'メモは500文字以内で入力してください'
  }
  return errors
}

export function toRegistrationItem(
  candidate: CandidateDraft,
): RecurringTransactionRegistrationItem {
  return {
    templateId: candidate.templateId,
    date: candidate.date,
    type: candidate.type,
    categoryId: candidate.type === 'TRANSFER' ? null : toNullableNumber(candidate.categoryId),
    paymentMethodId:
      candidate.type === 'EXPENSE' ? toNullableNumber(candidate.paymentMethodId) : null,
    transferSourceId:
      candidate.type === 'TRANSFER' ? toNullableNumber(candidate.transferSourceId) : null,
    transferDestinationId:
      candidate.type === 'TRANSFER' ? toNullableNumber(candidate.transferDestinationId) : null,
    amount: Number(candidate.amount),
    memo: candidate.memo.trim() === '' ? null : candidate.memo.trim(),
  }
}

export function applyTypeDefaults(target: TargetForm, masters: RecurringMasterData): void {
  target.categoryId = ''
  target.paymentMethodId = ''
  target.transferSourceId = ''
  target.transferDestinationId = ''
  if (target.type === 'TRANSFER') {
    target.transferSourceId = masters.transferAccounts[0]?.id ?? ''
    target.transferDestinationId = masters.transferAccounts[0]?.id ?? ''
  } else {
    target.categoryId = categoriesForType(target.type, masters)[0]?.id ?? ''
    target.paymentMethodId = target.type === 'EXPENSE' ? (masters.paymentMethods[0]?.id ?? '') : ''
  }
}

export function categoriesForType(
  type: TransactionType,
  masters: RecurringMasterData,
): readonly Category[] {
  return type === 'TRANSFER' ? [] : masters.categories.filter((category) => category.type === type)
}

export function primaryLabel(type: TransactionType): string {
  return type === 'TRANSFER' ? '振替元' : 'カテゴリ'
}

export function secondaryLabel(type: TransactionType): string {
  return type === 'TRANSFER' ? '振替先' : '支払い方法'
}

function validateTargets(target: TargetForm, masters: RecurringMasterData): TargetErrors {
  const errors: TargetErrors = {}
  if (target.type === 'TRANSFER') {
    if (!hasTransferAccount(target.transferSourceId, masters)) {
      errors.transferSourceId = '振替元を選択してください'
    }
    if (!hasTransferAccount(target.transferDestinationId, masters)) {
      errors.transferDestinationId = '振替先を選択してください'
    }
  } else {
    if (!hasCategory(target.categoryId, target.type, masters)) {
      errors.categoryId = '種別に合うカテゴリを選択してください'
    }
    if (target.type === 'EXPENSE' && !hasPaymentMethod(target.paymentMethodId, masters)) {
      errors.paymentMethodId = '支払い方法を選択してください'
    }
  }
  return errors
}

function hasCategory(
  value: OptionalNumber,
  type: TransactionType,
  masters: RecurringMasterData,
): boolean {
  return (
    value !== '' &&
    masters.categories.some((category) => category.id === value && category.type === type)
  )
}

function hasPaymentMethod(value: OptionalNumber, masters: RecurringMasterData): boolean {
  return value !== '' && masters.paymentMethods.some((method) => method.id === value)
}

function hasTransferAccount(value: OptionalNumber, masters: RecurringMasterData): boolean {
  return value !== '' && masters.transferAccounts.some((account) => account.id === value)
}

function isDateInSelectedMonth(value: string, startDate: string, endDate: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= startDate && value <= endDate
}

function isIntegerInRange(value: OptionalNumber, min: number, max?: number): boolean {
  const number = Number(value)
  return (
    value !== '' &&
    Number.isInteger(number) &&
    number >= min &&
    (max === undefined || number <= max)
  )
}

function toNullableNumber(value: OptionalNumber): number | null {
  return value === '' ? null : Number(value)
}
