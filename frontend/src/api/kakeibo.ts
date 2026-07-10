import { apiRequest, clearCsrfToken } from './http'

export type TransactionType = 'EXPENSE' | 'INCOME' | 'TRANSFER'
export type CategoryType = Exclude<TransactionType, 'TRANSFER'>

export type Category = {
  id: number
  name: string
  type: CategoryType
  displayOrder: number
}

export type CategoryRequest = {
  name: string
  type: CategoryType
  displayOrder: number
}

export type PaymentMethod = {
  id: number
  name: string
  displayOrder: number
}

export type PaymentMethodRequest = {
  name: string
  displayOrder: number
}

export type TransferAccount = {
  id: number
  name: string
  displayOrder: number
}

export type TransferAccountRequest = {
  name: string
  displayOrder: number
}

export type Transaction = {
  id: number
  date: string
  type: TransactionType
  categoryId: number
  categoryName: string
  paymentMethodId: number
  paymentMethodName: string
  amount: number
  memo: string | null
  displayOrder: number
}

export type TransactionMonthlySaveRequest = {
  id: number | null
  date: string | null
  type: TransactionType | null
  categoryId: number | null
  paymentMethodId: number | null
  amount: number | null
  memo: string | null
  displayOrder: number
}

export type TransactionMonthlySaveResponse = {
  status: string
}

export type MonthlySummary = {
  year: number
  month: number
  incomeTotal: number
  expenseTotal: number
  balance: number
}

export type AuthUser = {
  username: string
  twoFactorEnabled: boolean
}

export type LoginRequest = {
  username: string
  password: string
}

export type ChangePasswordRequest = {
  currentPassword: string
  newPassword: string
  newPasswordConfirm: string
}

export function getHello(): Promise<string> {
  return apiRequest<string>('/hello')
}

export function login(request: LoginRequest): Promise<AuthUser> {
  return apiRequest<AuthUser>('/api/login', {
    method: 'POST',
    body: request,
  })
}

export async function logout(): Promise<void> {
  await apiRequest<void>('/api/logout', {
    method: 'POST',
  })
  clearCsrfToken()
}

export function getCurrentUser(): Promise<AuthUser> {
  return apiRequest<AuthUser>('/api/me')
}

export function changePassword(request: ChangePasswordRequest): Promise<void> {
  return apiRequest<void>('/api/me/password', {
    method: 'PUT',
    body: request,
  })
}

export function getCategories(): Promise<Category[]> {
  return apiRequest<Category[]>('/api/categories')
}

export function createCategory(request: CategoryRequest): Promise<Category> {
  return apiRequest<Category>('/api/categories', {
    method: 'POST',
    body: request,
  })
}

export function updateCategory(id: number, request: CategoryRequest): Promise<Category> {
  return apiRequest<Category>(`/api/categories/${id}`, {
    method: 'PUT',
    body: request,
  })
}

export function deleteCategory(id: number): Promise<void> {
  return apiRequest<void>(`/api/categories/${id}`, {
    method: 'DELETE',
  })
}

export function getPaymentMethods(): Promise<PaymentMethod[]> {
  return apiRequest<PaymentMethod[]>('/api/payment-methods')
}

export function createPaymentMethod(request: PaymentMethodRequest): Promise<PaymentMethod> {
  return apiRequest<PaymentMethod>('/api/payment-methods', {
    method: 'POST',
    body: request,
  })
}

export function updatePaymentMethod(
  id: number,
  request: PaymentMethodRequest,
): Promise<PaymentMethod> {
  return apiRequest<PaymentMethod>(`/api/payment-methods/${id}`, {
    method: 'PUT',
    body: request,
  })
}

export function deletePaymentMethod(id: number): Promise<void> {
  return apiRequest<void>(`/api/payment-methods/${id}`, {
    method: 'DELETE',
  })
}

export function getTransferAccounts(): Promise<TransferAccount[]> {
  return apiRequest<TransferAccount[]>('/api/transfer-accounts')
}

export function createTransferAccount(
  request: TransferAccountRequest,
): Promise<TransferAccount> {
  return apiRequest<TransferAccount>('/api/transfer-accounts', {
    method: 'POST',
    body: request,
  })
}

export function updateTransferAccount(
  id: number,
  request: TransferAccountRequest,
): Promise<TransferAccount> {
  return apiRequest<TransferAccount>(`/api/transfer-accounts/${id}`, {
    method: 'PUT',
    body: request,
  })
}

export function deleteTransferAccount(id: number): Promise<void> {
  return apiRequest<void>(`/api/transfer-accounts/${id}`, {
    method: 'DELETE',
  })
}

export function getTransactions(year: number, month: number): Promise<Transaction[]> {
  return apiRequest<Transaction[]>(`/api/transactions?year=${year}&month=${month}`)
}

export function saveMonthlyTransactions(
  year: number,
  month: number,
  requests: TransactionMonthlySaveRequest[],
): Promise<TransactionMonthlySaveResponse> {
  return apiRequest<TransactionMonthlySaveResponse>(
    `/api/transactions/monthly?year=${year}&month=${month}`,
    {
      method: 'PUT',
      body: requests,
    },
  )
}

export function getMonthlySummary(year: number, month: number): Promise<MonthlySummary> {
  return apiRequest<MonthlySummary>(`/api/summary/monthly?year=${year}&month=${month}`)
}
