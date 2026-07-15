import type { Transaction, TransactionMonthlySaveRequest, TransactionType } from '@/api/kakeibo'

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
  deleted: boolean
}

export type TransactionField =
  | 'id'
  | 'date'
  | 'type'
  | 'categoryId'
  | 'paymentMethodId'
  | 'amount'
  | 'memo'
  | 'displayOrder'

export type TransactionFieldErrors = Partial<Record<TransactionField, string>>

export type EditableField = 'date' | 'type' | 'categoryId' | 'paymentMethodId' | 'amount' | 'memo'

export type RowEditSnapshot = Pick<
  TransactionRow,
  'date' | 'type' | 'categoryId' | 'paymentMethodId' | 'amount' | 'memo' | 'deleted'
>

export type SaveEntry = {
  row: TransactionRow
  request: TransactionMonthlySaveRequest
}

export type RowDefaults = {
  date: string
  categoryId: number | ''
  paymentMethodId: number | ''
}

export const EDITABLE_FIELDS: EditableField[] = [
  'date',
  'type',
  'categoryId',
  'paymentMethodId',
  'amount',
  'memo',
]

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
    deleted: false,
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
    deleted: false,
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
    deleted: false,
  }
}

export function snapshotRow(row: TransactionRow): RowEditSnapshot {
  return {
    date: row.date,
    type: row.type,
    categoryId: row.categoryId,
    paymentMethodId: row.paymentMethodId,
    amount: row.amount,
    memo: row.memo,
    deleted: row.deleted,
  }
}

export function applySnapshot(row: TransactionRow, snapshot: RowEditSnapshot): void {
  row.date = snapshot.date
  row.type = snapshot.type
  row.categoryId = snapshot.categoryId
  row.paymentMethodId = snapshot.paymentMethodId
  row.amount = snapshot.amount
  row.memo = snapshot.memo
  row.deleted = snapshot.deleted
}

export function isBlankNewRow(row: TransactionRow): boolean {
  return row.id === null && row.amount === '' && row.memo.trim() === ''
}

export function isUnsavedEnteredRow(row: TransactionRow): boolean {
  return row.id === null && !isBlankNewRow(row)
}

export function isTransactionField(field: string): field is TransactionField {
  return (
    field === 'id' ||
    field === 'date' ||
    field === 'type' ||
    field === 'categoryId' ||
    field === 'paymentMethodId' ||
    field === 'amount' ||
    field === 'memo' ||
    field === 'displayOrder'
  )
}

export function toRequest(
  row: TransactionRow,
  displayOrder: number,
): TransactionMonthlySaveRequest {
  return {
    id: row.id,
    date: row.date || null,
    type: row.type,
    categoryId: row.categoryId === '' ? null : row.categoryId,
    paymentMethodId: row.paymentMethodId === '' ? null : row.paymentMethodId,
    amount: row.amount === '' ? null : Number(row.amount),
    memo: row.memo.trim() === '' ? null : row.memo.trim(),
    displayOrder,
  }
}

/** 削除済みと未入力の空行を除いた、サーバへ送る行だけを組み立てる。 */
export function buildSaveEntries(rows: TransactionRow[]): SaveEntry[] {
  return rows
    .filter((row) => !row.deleted)
    .filter((row) => !isBlankNewRow(row))
    .map((row, index) => ({
      row,
      request: toRequest(row, index * 10),
    }))
}

export function snapshotOf(entries: SaveEntry[]): string {
  return JSON.stringify(entries.map((entry) => entry.request))
}
