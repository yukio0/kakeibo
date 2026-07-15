import { apiBlobRequest, apiRequest, clearCsrfToken } from './http'

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
  // 収入は支払い方法を持たないため null になり得る。
  paymentMethodId: number | null
  paymentMethodName: string | null
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

export type TransactionSaveRequest = {
  date: string | null
  type: TransactionType
  categoryId: number | null
  paymentMethodId: number | null
  amount: number | null
  memo: string | null
}

export type MonthlySummary = {
  year: number
  month: number
  incomeTotal: number
  expenseTotal: number
  balance: number
}

export type CategoryExpense = {
  categoryId: number
  categoryName: string
  total: number
}

export type CategoryExpenseSummary = {
  year: number
  month: number
  expenseTotal: number
  categories: CategoryExpense[]
}

export type MonthlyTrend = {
  months: MonthlySummary[]
}

export type DailySummaryItem = {
  date: string
  incomeTotal: number
  expenseTotal: number
}

export type DailySummary = {
  year: number
  month: number
  days: DailySummaryItem[]
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

export type LoginResponse = {
  mfaRequired: boolean
  user: AuthUser | null
}

export type MfaStatus = {
  enabled: boolean
}

export type SecuritySettings = {
  authenticationEnabled: boolean
  twoFactorEnabled: boolean
}

export type MfaSetup = {
  secret: string
  otpauthUri: string
  qrCodeSvg: string
}

export type MfaCodeRequest = {
  code: string
}

export type MfaVerifyRequest = {
  code: string
  trustDevice: boolean
}

export type TrustedDevice = {
  id: number
  deviceName: string
  lastUsedAt: string | null
  expiresAt: string
  createdAt: string | null
  current: boolean
}

export function getHello(): Promise<string> {
  return apiRequest<string>('/hello')
}

export function login(request: LoginRequest): Promise<LoginResponse> {
  return apiRequest<LoginResponse>('/api/login', {
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

export function getSecuritySettings(): Promise<SecuritySettings> {
  return apiRequest<SecuritySettings>('/api/security-settings')
}

export function changePassword(request: ChangePasswordRequest): Promise<void> {
  return apiRequest<void>('/api/me/password', {
    method: 'PUT',
    body: request,
  })
}

export function getMfaStatus(): Promise<MfaStatus> {
  return apiRequest<MfaStatus>('/api/mfa/status')
}

export function setupMfa(): Promise<MfaSetup> {
  return apiRequest<MfaSetup>('/api/mfa/setup')
}

export function enableMfa(request: MfaCodeRequest): Promise<void> {
  return apiRequest<void>('/api/mfa/enable', {
    method: 'POST',
    body: request,
  })
}

export function verifyMfa(request: MfaVerifyRequest): Promise<AuthUser> {
  return apiRequest<AuthUser>('/api/mfa/verify', {
    method: 'POST',
    body: request,
  })
}

export function disableMfa(): Promise<void> {
  return apiRequest<void>('/api/mfa/disable', {
    method: 'POST',
  })
}

export function getTrustedDevices(): Promise<TrustedDevice[]> {
  return apiRequest<TrustedDevice[]>('/api/trusted-devices')
}

export function revokeTrustedDevice(id: number): Promise<void> {
  return apiRequest<void>(`/api/trusted-devices/${id}`, {
    method: 'DELETE',
  })
}

export function revokeCurrentTrustedDevice(): Promise<void> {
  return apiRequest<void>('/api/trusted-devices/current', {
    method: 'DELETE',
  })
}

export function revokeAllTrustedDevices(): Promise<void> {
  return apiRequest<void>('/api/trusted-devices', {
    method: 'DELETE',
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

export function createTransferAccount(request: TransferAccountRequest): Promise<TransferAccount> {
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

export function createTransaction(
  year: number,
  month: number,
  request: TransactionSaveRequest,
): Promise<Transaction> {
  return apiRequest<Transaction>(`/api/transactions?year=${year}&month=${month}`, {
    method: 'POST',
    body: request,
  })
}

export function updateTransaction(
  id: number,
  year: number,
  month: number,
  request: TransactionSaveRequest,
): Promise<Transaction> {
  return apiRequest<Transaction>(`/api/transactions/${id}?year=${year}&month=${month}`, {
    method: 'PUT',
    body: request,
  })
}

export function deleteTransaction(id: number, year: number, month: number): Promise<void> {
  return apiRequest<void>(`/api/transactions/${id}?year=${year}&month=${month}`, {
    method: 'DELETE',
  })
}

export type TransactionImportMonth = {
  year: number
  month: number
  replacedCount: number
  importedCount: number
}

export type TransactionImportError = {
  row: number
  message: string
}

export type TransactionImportResult = {
  committed: boolean
  totalRows: number
  months: TransactionImportMonth[]
  errors: TransactionImportError[]
}

export function importTransactions(file: File, commit: boolean): Promise<TransactionImportResult> {
  const form = new FormData()
  form.append('file', file)
  form.append('commit', String(commit))

  return apiRequest<TransactionImportResult>('/api/transactions/import', {
    method: 'POST',
    body: form,
    fallbackMessage: 'CSVの取り込みに失敗しました',
  })
}

export function exportTransactions(startDate?: string, endDate?: string): Promise<Blob | null> {
  const searchParams = new URLSearchParams()
  if (startDate) {
    searchParams.set('startDate', startDate)
  }
  if (endDate) {
    searchParams.set('endDate', endDate)
  }
  const query = searchParams.size > 0 ? `?${searchParams}` : ''

  return apiBlobRequest(`/api/transactions/export${query}`, {
    fallbackMessage: 'CSVの出力に失敗しました',
  })
}

/** 保存後の家計簿データを、渡した `requests` と同じ並びで返す。 */
export function saveMonthlyTransactions(
  year: number,
  month: number,
  requests: TransactionMonthlySaveRequest[],
): Promise<Transaction[]> {
  return apiRequest<Transaction[]>(`/api/transactions/monthly?year=${year}&month=${month}`, {
    method: 'PUT',
    body: requests,
  })
}

export function getMonthlySummary(year: number, month: number): Promise<MonthlySummary> {
  return apiRequest<MonthlySummary>(`/api/summary/monthly?year=${year}&month=${month}`)
}

export function getMonthlyDailySummary(year: number, month: number): Promise<DailySummary> {
  return apiRequest<DailySummary>(`/api/summary/monthly/daily?year=${year}&month=${month}`)
}

export function getMonthlyCategoryExpenses(
  year: number,
  month: number,
): Promise<CategoryExpenseSummary> {
  return apiRequest<CategoryExpenseSummary>(
    `/api/summary/monthly/categories?year=${year}&month=${month}`,
  )
}

export function getMonthlyTrend(
  year: number,
  month: number,
  months: number,
): Promise<MonthlyTrend> {
  return apiRequest<MonthlyTrend>(`/api/summary/trend?year=${year}&month=${month}&months=${months}`)
}
