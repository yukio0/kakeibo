import { expect, test, type Page } from '@playwright/test'
import { loginThroughMfa, resetE2eData, saveScreenshot } from './support/test-support'

type NamedItem = { id: number; name: string }
type Transaction = { amount: number }

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await page.context().clearCookies()
})

function currentMonth(): { year: number; month: number; date: string } {
  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth() + 1
  return { year, month, date: `${year}-${String(month).padStart(2, '0')}-15` }
}

function csvBuffer(rows: string[][]): Buffer {
  const header = ['日付', '種別', 'カテゴリ・振替元', '支払い方法・振替先', '金額', 'メモ']
  const encode = (row: string[]) => row.map((value) => `"${value.replace(/"/g, '""')}"`).join(',')
  const content = [header, ...rows].map(encode).join('\r\n') + '\r\n'
  return Buffer.from(content, 'utf-8')
}

async function csrfHeader(page: Page): Promise<Record<string, string>> {
  const response = await page.request.get('/api/csrf')
  const csrf = (await response.json()) as { headerName: string; token: string }
  return { [csrf.headerName]: csrf.token }
}

async function fetchItems(page: Page, path: string): Promise<NamedItem[]> {
  return (await (await page.request.get(path)).json()) as NamedItem[]
}

async function createExpense(
  page: Page,
  target: { year: number; month: number; date: string },
  amount: number,
): Promise<void> {
  const headers = await csrfHeader(page)
  const categories = await fetchItems(page, '/api/categories')
  const paymentMethods = await fetchItems(page, '/api/payment-methods')
  const foodId = categories.find((item) => item.name === '食費')?.id
  const cashId = paymentMethods.find((item) => item.name === '現金')?.id
  const response = await page.request.post(
    `/api/transactions?year=${target.year}&month=${target.month}`,
    {
      headers,
      data: {
        date: target.date,
        type: 'EXPENSE',
        categoryId: foodId,
        paymentMethodId: cashId,
        amount,
        memo: null,
      },
    },
  )
  expect(response.ok()).toBeTruthy()
}

async function monthTransactions(page: Page, year: number, month: number): Promise<Transaction[]> {
  const response = await page.request.get(`/api/transactions?year=${year}&month=${month}`)
  return (await response.json()) as Transaction[]
}

test('CSVインポート: プレビューから取り込みで登録される', async ({ page }, testInfo) => {
  await loginThroughMfa(page)
  const target = currentMonth()

  await page.getByRole('link', { name: 'CSV入出力', exact: true }).click()

  const buffer = csvBuffer([
    [target.date, '支出', '食費', '現金', '5000', '昼食'],
    [target.date, '収入', '給与', '現金', '300000', ''],
  ])
  await page.locator('input[type="file"]').setInputFiles({
    name: 'import.csv',
    mimeType: 'text/csv',
    buffer,
  })

  // プレビュー: 対象月が空なので既存0件・取り込み2件
  const planRows = page.locator('.import-plan-table tbody tr')
  await expect(planRows).toHaveCount(1)
  await expect(planRows.nth(0)).toContainText('0件')
  await expect(planRows.nth(0)).toContainText('2件')
  await saveScreenshot(page, testInfo, 'csv-import-preview')

  await page.getByRole('button', { name: '取り込む', exact: true }).click()
  await expect(page.getByText('2件を取り込みました。', { exact: true })).toBeVisible()

  const transactions = await monthTransactions(page, target.year, target.month)
  expect(transactions.length).toBe(2)
})

test('CSVインポート: 対象月の既存データを上書きする', async ({ page }) => {
  await loginThroughMfa(page)
  const target = currentMonth()
  await createExpense(page, target, 1000)

  await page.getByRole('link', { name: 'CSV入出力', exact: true }).click()

  const buffer = csvBuffer([
    [target.date, '支出', '食費', '現金', '3000', '置換1'],
    [target.date, '支出', '食費', '現金', '500', '置換2'],
  ])
  await page.locator('input[type="file"]').setInputFiles({
    name: 'import.csv',
    mimeType: 'text/csv',
    buffer,
  })

  // 既存1件 → 取り込み2件
  const planRow = page.locator('.import-plan-table tbody tr').nth(0)
  await expect(planRow).toContainText('1件')
  await expect(planRow).toContainText('2件')

  await page.getByRole('button', { name: '取り込む', exact: true }).click()
  await expect(page.getByText('2件を取り込みました。', { exact: true })).toBeVisible()

  const amounts = (await monthTransactions(page, target.year, target.month))
    .map((transaction) => transaction.amount)
    .sort((left, right) => left - right)
  expect(amounts).toEqual([500, 3000])
})
