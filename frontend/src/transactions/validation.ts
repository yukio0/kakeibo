import type {
  Category,
  PaymentMethod,
  TransactionSaveRequest,
  TransferAccount,
} from '@/api/kakeibo'
import { isValidDateString } from './dates'
import type { TransactionFieldErrors } from './rowModel'

export type ValidationContext = {
  categories: Category[]
  paymentMethods: PaymentMethod[]
  transferAccounts: TransferAccount[]
  monthStartDate: string
  monthEndDate: string
}

/**
 * 個別保存前にセルへエラーを出すための、サーバ側の検証規則の先取り。
 *
 * 画面側で早めにエラーを示し、明らかに不正な内容をサーバへ投げないための門番でもある。
 * ここの規則はサーバの `TransactionService` と対になっていて、片方だけ変えると画面とAPIで挙動がずれる。
 *
 * @returns フィールドをキーにしたエラー。空なら妥当。
 */
export function validateTransaction(
  request: TransactionSaveRequest,
  context: ValidationContext,
): TransactionFieldErrors {
  const errors: TransactionFieldErrors = {}

  if (!request.date) {
    errors.date = '日付を入力してください'
  } else if (!isValidDateString(request.date)) {
    errors.date = '日付の形式が不正です'
  } else if (request.date < context.monthStartDate || request.date > context.monthEndDate) {
    errors.date = '対象月の日付を入力してください'
  }

  const isTransfer = request.type === 'TRANSFER'
  const categoryMessage = isTransfer
    ? transferAccountError(request.categoryId, context, '振替元')
    : categoryError(request.categoryId, request.type, context)
  if (categoryMessage) {
    errors.categoryId = categoryMessage
  }

  // 収入は支払い方法を持たないため検証しない。
  const paymentMethodMessage = isTransfer
    ? transferAccountError(request.paymentMethodId, context, '振替先')
    : request.type === 'INCOME'
      ? undefined
      : paymentMethodError(request.paymentMethodId, context)
  if (paymentMethodMessage) {
    errors.paymentMethodId = paymentMethodMessage
  }

  if (request.amount === null) {
    errors.amount = '金額を入力してください'
  } else if (!Number.isInteger(request.amount) || request.amount < 1) {
    errors.amount = '金額は1以上の整数で入力してください'
  }

  if ((request.memo?.length ?? 0) > 500) {
    errors.memo = 'メモは500文字以内で入力してください'
  }

  return errors
}

function transferAccountError(
  id: number | null,
  context: ValidationContext,
  label: '振替元' | '振替先',
): string | undefined {
  if (id === null) {
    return `${label}を選択してください`
  }
  if (!context.transferAccounts.some((account) => account.id === id)) {
    return `${label}が見つかりません`
  }
  return undefined
}

function categoryError(
  id: number | null,
  type: TransactionSaveRequest['type'],
  context: ValidationContext,
): string | undefined {
  if (id === null) {
    return 'カテゴリを選択してください'
  }

  const selected = context.categories.find((category) => category.id === id)
  if (!selected) {
    return 'カテゴリが見つかりません'
  }
  if (selected.type !== type) {
    return '種別に合うカテゴリを選択してください'
  }
  return undefined
}

function paymentMethodError(id: number | null, context: ValidationContext): string | undefined {
  if (id === null) {
    return '支払い方法を選択してください'
  }
  if (!context.paymentMethods.some((paymentMethod) => paymentMethod.id === id)) {
    return '支払い方法が見つかりません'
  }
  return undefined
}
