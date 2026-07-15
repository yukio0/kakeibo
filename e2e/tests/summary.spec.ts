import { expect, test, type Page } from '@playwright/test'
import { loginThroughMfa, resetE2eData, saveScreenshot } from './support/test-support'

type NamedItem = { id: number; name: string }

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await page.context().clearCookies()
})

async function csrfHeader(page: Page): Promise<Record<string, string>> {
  const response = await page.request.get('/api/csrf')
  expect(response.ok()).toBeTruthy()
  const csrf = (await response.json()) as { headerName: string; token: string }
  return { [csrf.headerName]: csrf.token }
}

async function fetchItems(page: Page, path: string): Promise<NamedItem[]> {
  const response = await page.request.get(path)
  expect(response.ok()).toBeTruthy()
  return (await response.json()) as NamedItem[]
}

function idByName(items: NamedItem[], name: string): number {
  const found = items.find((item) => item.name === name)
  if (!found) {
    throw new Error(`項目が見つかりません: ${name}`)
  }
  return found.id
}

async function createExpenseCategory(
  page: Page,
  headers: Record<string, string>,
  name: string,
  displayOrder: number,
): Promise<void> {
  const response = await page.request.post('/api/categories', {
    headers,
    data: { name, type: 'EXPENSE', displayOrder },
  })
  expect(response.ok()).toBeTruthy()
}

async function createTransaction(
  page: Page,
  headers: Record<string, string>,
  target: { year: number; month: number; date: string },
  type: 'INCOME' | 'EXPENSE',
  categoryId: number,
  paymentMethodId: number,
  amount: number,
): Promise<void> {
  const response = await page.request.post(
    `/api/transactions?year=${target.year}&month=${target.month}`,
    {
      headers,
      data: { date: target.date, type, categoryId, paymentMethodId, amount, memo: null },
    },
  )
  expect(response.ok()).toBeTruthy()
}

function monthAt(offset: number): { year: number; month: number; date: string } {
  const now = new Date()
  const target = new Date(now.getFullYear(), now.getMonth() + offset, 15)
  const year = target.getFullYear()
  const month = target.getMonth() + 1
  const date = `${year}-${String(month).padStart(2, '0')}-15`
  return { year, month, date }
}

test('集計画面: カテゴリ別支出の円グラフを表示する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)

  const headers = await csrfHeader(page)
  await createExpenseCategory(page, headers, '交通費', 20)
  await createExpenseCategory(page, headers, '娯楽', 30)

  const categories = await fetchItems(page, '/api/categories')
  const paymentMethods = await fetchItems(page, '/api/payment-methods')
  const cashId = idByName(paymentMethods, '現金')
  const current = monthAt(0)

  await createTransaction(
    page,
    headers,
    current,
    'EXPENSE',
    idByName(categories, '食費'),
    cashId,
    5000,
  )
  await createTransaction(
    page,
    headers,
    current,
    'EXPENSE',
    idByName(categories, '交通費'),
    cashId,
    3000,
  )
  await createTransaction(
    page,
    headers,
    current,
    'EXPENSE',
    idByName(categories, '娯楽'),
    cashId,
    2000,
  )

  await page.getByRole('link', { name: '集計', exact: true }).click()

  await expect(page.locator('.donut-arc')).toHaveCount(3)
  await expect(page.locator('.donut-center-value')).toContainText('10,000')

  const legendRows = page.locator('.category-legend tbody tr')
  await expect(legendRows).toHaveCount(3)
  await expect(legendRows.nth(0)).toContainText('食費')
  await expect(legendRows.nth(0)).toContainText('50.0%')
  await expect(legendRows.nth(1)).toContainText('交通費')
  await expect(legendRows.nth(1)).toContainText('30.0%')
  await expect(legendRows.nth(2)).toContainText('娯楽')
  await expect(legendRows.nth(2)).toContainText('20.0%')

  await saveScreenshot(page, testInfo, 'summary-pie-chart')
})

test('集計画面: 月全体とカテゴリ別の予算を自動保存する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)

  const headers = await csrfHeader(page)
  const categories = await fetchItems(page, '/api/categories')
  const paymentMethods = await fetchItems(page, '/api/payment-methods')
  const current = monthAt(0)

  await createTransaction(
    page,
    headers,
    current,
    'EXPENSE',
    idByName(categories, '食費'),
    idByName(paymentMethods, '現金'),
    12000,
  )

  await page.getByRole('link', { name: '集計', exact: true }).click()

  const overallBudgetInput = page.getByRole('spinbutton', { name: '月全体の予算' })
  await expect(overallBudgetInput).toHaveValue('')

  const overallSave = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === '/api/budgets/monthly',
  )
  await overallBudgetInput.fill('10000')
  await overallBudgetInput.blur()
  await overallSave

  const overall = page.locator('.budget-overall')
  await expect(overall).toContainText('12,000')
  await expect(overall).toContainText('2,000')

  const foodBudgetInput = page.getByRole('spinbutton', { name: '食費の予算' })
  const categorySave = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === '/api/budgets/monthly',
  )
  await foodBudgetInput.fill('15000')
  await foodBudgetInput.blur()
  await categorySave

  const foodBudgetRow = page.locator('.budget-table tbody tr').filter({ hasText: '食費' })
  await expect(foodBudgetRow).toContainText('12,000')
  await expect(foodBudgetRow).toContainText('3,000')

  await page.reload()
  await expect(overallBudgetInput).toHaveValue('10000')
  await expect(foodBudgetInput).toHaveValue('15000')

  await saveScreenshot(page, testInfo, 'summary-budget')
})

test('集計画面: 月次の収支推移グラフを表示する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)

  const headers = await csrfHeader(page)
  const categories = await fetchItems(page, '/api/categories')
  const paymentMethods = await fetchItems(page, '/api/payment-methods')
  const foodId = idByName(categories, '食費')
  const salaryId = idByName(categories, '給与')
  const cashId = idByName(paymentMethods, '現金')

  // 直近3か月に収支を投入する。2か月前は支出が収入を上回り、差額がマイナスになる。
  const plans = [
    { offset: 0, income: 300000, expense: 150000 },
    { offset: -1, income: 300000, expense: 220000 },
    { offset: -2, income: 300000, expense: 350000 },
  ]
  for (const plan of plans) {
    const target = monthAt(plan.offset)
    await createTransaction(page, headers, target, 'INCOME', salaryId, cashId, plan.income)
    await createTransaction(page, headers, target, 'EXPENSE', foodId, cashId, plan.expense)
  }

  await page.getByRole('link', { name: '集計', exact: true }).click()

  // 最初に記録した月(2か月前)から当月までの3か月分の棒(収入・支出)と差額の折れ線が描画される。
  await expect(page.locator('.trend-bar-income')).toHaveCount(3)
  await expect(page.locator('.trend-bar-expense')).toHaveCount(3)
  await expect(page.locator('.trend-line-balance')).toBeVisible()

  // Y軸の目盛りラベル(万円表記)が複数描画される。
  await expect(page.locator('.trend-y-label').filter({ hasText: '万' }).first()).toBeVisible()
  expect(await page.locator('.trend-y-label').count()).toBeGreaterThan(1)

  const trendRows = page.locator('.trend-table tbody tr')
  await expect(trendRows).toHaveCount(3)
  // 末尾が当月。当月の支出150,000が表示される。
  await expect(trendRows.nth(2)).toContainText('150,000')
  // 先頭が2か月前で、差額がマイナスのためマイナス表示のセルになる。
  await expect(trendRows.nth(0).locator('td.trend-negative')).toBeVisible()

  await saveScreenshot(page, testInfo, 'summary-trend-chart')
})

test('集計画面: 最初に入力した日から日別の収支を表示する', async ({ page }) => {
  await loginThroughMfa(page)

  const headers = await csrfHeader(page)
  const categories = await fetchItems(page, '/api/categories')
  const paymentMethods = await fetchItems(page, '/api/payment-methods')
  const current = monthAt(0)
  const firstDate = current.date.replace(/-15$/, '-10')
  const incomeDate = current.date.replace(/-15$/, '-12')
  const cashId = idByName(paymentMethods, '現金')

  await createTransaction(
    page,
    headers,
    { ...current, date: firstDate },
    'EXPENSE',
    idByName(categories, '食費'),
    cashId,
    1000,
  )
  await createTransaction(
    page,
    headers,
    { ...current, date: incomeDate },
    'INCOME',
    idByName(categories, '給与'),
    cashId,
    2000,
  )

  await page.getByRole('link', { name: '集計', exact: true }).click()

  const dailyRows = page.locator('.daily-table tbody tr')
  const lastDay = new Date(current.year, current.month, 0).getDate()
  await expect(dailyRows).toHaveCount(lastDay - 10 + 1)
  await expect(dailyRows.nth(0)).toContainText(`${current.month}/10`)
  await expect(dailyRows.nth(0)).toContainText('1,000')
  await expect(dailyRows.nth(2)).toContainText(`${current.month}/12`)
  await expect(dailyRows.nth(2)).toContainText('2,000')
})
