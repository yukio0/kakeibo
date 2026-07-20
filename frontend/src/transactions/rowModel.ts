import type { Transaction, TransactionSaveRequest, TransactionType } from '@/api/kakeibo'

/** 画面上の1行。`id` が null なら未保存、`localKey` は保存前後で変わらない行の同一性。 */
export type TransactionRow = {
  localKey: string
  id: number | null
  date: string
  type: TransactionType
  categoryId: number | ''
  paymentMethodId: number | ''
  amount: number | ''
  memo: string
}

export type TransactionField =
  | 'id'
  | 'date'
  | 'type'
  | 'categoryId'
  | 'paymentMethodId'
  | 'amount'
  | 'memo'

export type TransactionFieldErrors = Partial<Record<TransactionField, string>>

export type EditableField = 'date' | 'type' | 'categoryId' | 'paymentMethodId' | 'amount' | 'memo'

export type RowDefaults = {
  date: string
  categoryId: number | ''
  paymentMethodId: number | ''
}

let nextLocalId = 1

function createLocalKey(): string {
  const key = `row-${nextLocalId}`
  nextLocalId += 1
  return key
}

export function toRow(transaction: Transaction): TransactionRow {
  return {
    localKey: createLocalKey(),
    id: transaction.id,
    date: transaction.date,
    type: transaction.type,
    categoryId: transaction.categoryId,
    paymentMethodId: transaction.paymentMethodId ?? '',
    amount: transaction.amount,
    memo: transaction.memo ?? '',
  }
}

export function createEmptyRow(defaults: RowDefaults): TransactionRow {
  return {
    localKey: createLocalKey(),
    id: null,
    date: defaults.date,
    type: 'EXPENSE',
    categoryId: defaults.categoryId,
    paymentMethodId: defaults.paymentMethodId,
    amount: '',
    memo: '',
  }
}

export function createCopiedRow(row: TransactionRow): TransactionRow {
  return {
    localKey: createLocalKey(),
    id: null,
    date: row.date,
    type: row.type,
    categoryId: row.categoryId,
    paymentMethodId: row.paymentMethodId,
    amount: row.amount,
    memo: row.memo,
  }
}

export function isBlankNewRow(row: TransactionRow): boolean {
  return row.id === null && row.amount === '' && row.memo.trim() === ''
}

export function isTransactionField(field: string): field is TransactionField {
  return (
    field === 'id' ||
    field === 'date' ||
    field === 'type' ||
    field === 'categoryId' ||
    field === 'paymentMethodId' ||
    field === 'amount' ||
    field === 'memo'
  )
}

export function toSaveRequest(row: TransactionRow): TransactionSaveRequest {
  return {
    date: row.date || null,
    type: row.type,
    categoryId: row.categoryId === '' ? null : row.categoryId,
    paymentMethodId: row.paymentMethodId === '' ? null : row.paymentMethodId,
    amount: row.amount === '' ? null : Number(row.amount),
    memo: row.memo.trim() === '' ? null : row.memo.trim(),
  }
}
