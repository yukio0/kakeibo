import { expect, test, type Page } from '@playwright/test'
import { loginThroughMfa, resetE2eData } from './support/test-support'

type Master = {
  id: number
  name: string
}

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await loginThroughMfa(page)
})

async function csrfHeader(page: Page): Promise<Record<string, string>> {
  const response = await page.request.get('/api/csrf')
  expect(response.ok()).toBeTruthy()
  const csrf = (await response.json()) as { headerName: string; token: string }
  return { [csrf.headerName]: csrf.token }
}

test('主要画面が境界幅でもはみ出さず、表のセルが揃う', async ({ page }) => {
  await page.setViewportSize({ width: 1220, height: 900 })
  await expect(page.getByRole('button', { name: 'メニュー', exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'メインナビゲーション' })).toBeHidden()

  const headerHeight = await page
    .locator('.app-header')
    .evaluate((header) => Math.round(header.getBoundingClientRect().height))
  expect(headerHeight).toBeLessThanOrEqual(80)

  await page.setViewportSize({ width: 650, height: 900 })
  await page.goto('/categories')
  await expect(page.getByRole('heading', { name: 'カテゴリ管理', exact: true })).toBeVisible()

  const categoryLayout = await page.locator('.category-create-form').evaluate((form) => {
    const card = form.closest('section')
    if (!card) {
      throw new Error('カテゴリ登録フォームのカードが見つかりません')
    }
    const formRect = form.getBoundingClientRect()
    const cardRect = card.getBoundingClientRect()
    const currentScrollY = window.scrollY
    window.scrollTo(1_000_000, currentScrollY)
    const maxHorizontalScroll = window.scrollX
    window.scrollTo(0, currentScrollY)
    return {
      cardRight: cardRect.right,
      formRight: formRect.right,
      maxHorizontalScroll,
    }
  })
  expect(categoryLayout.formRight).toBeLessThanOrEqual(categoryLayout.cardRight + 1)
  expect(categoryLayout.maxHorizontalScroll).toBe(0)

  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/csv-export')
  await expect(page.getByRole('heading', { name: 'CSV入出力', exact: true })).toBeVisible()

  const dateInputWidths = await page
    .locator('.csv-period-form input[type="date"]')
    .evaluateAll((inputs) => inputs.map((input) => input.getBoundingClientRect().width))
  expect(dateInputWidths).toHaveLength(2)
  expect(Math.abs(dateInputWidths[0] - dateInputWidths[1])).toBeLessThanOrEqual(1)

  await page.goto('/summary?month=2026-07')
  await expect(page.locator('.budget-table tbody tr').first()).toBeVisible()
  const budgetCellAlignments = await page
    .locator('.budget-table tbody tr')
    .first()
    .locator(':scope > th, :scope > td')
    .evaluateAll((cells) => cells.map((cell) => getComputedStyle(cell).verticalAlign))
  expect(budgetCellAlignments.every((alignment) => alignment === 'middle')).toBeTruthy()
})

test('家計簿の操作セルとモバイル表示が大きな金額でも崩れない', async ({ page }) => {
  const headers = await csrfHeader(page)
  const [categoriesResponse, paymentMethodsResponse] = await Promise.all([
    page.request.get('/api/categories'),
    page.request.get('/api/payment-methods'),
  ])
  expect(categoriesResponse.ok()).toBeTruthy()
  expect(paymentMethodsResponse.ok()).toBeTruthy()

  const categories = (await categoriesResponse.json()) as Master[]
  const paymentMethods = (await paymentMethodsResponse.json()) as Master[]
  const expenseCategory = categories.find((category) => category.name === '食費')
  const paymentMethod = paymentMethods.find((method) => method.name === '現金')
  if (!expenseCategory || !paymentMethod) {
    throw new Error('レイアウトテスト用のマスタが見つかりません')
  }

  const createResponse = await page.request.post('/api/transactions?year=2026&month=7', {
    headers,
    data: {
      date: '2026-07-19',
      type: 'EXPENSE',
      categoryId: expenseCategory.id,
      paymentMethodId: paymentMethod.id,
      amount: 999_999_999,
      memo: 'モバイルレイアウト確認',
    },
  })
  expect(createResponse.status()).toBe(201)

  await page.goto('/?month=2026-07')
  await expect(page.getByText('登録済み 1件', { exact: true })).toBeVisible()

  const tableCellLayout = await page
    .locator('.transaction-table tbody tr')
    .filter({ has: page.getByRole('button', { name: '更新', exact: true }) })
    .evaluate((row) => {
      const actionCell = row.querySelector<HTMLElement>('.table-actions-cell')
      if (!actionCell) {
        throw new Error('家計簿の操作セルが見つかりません')
      }
      return {
        actionDisplay: getComputedStyle(actionCell).display,
        actionHeight: actionCell.getBoundingClientRect().height,
        rowHeight: row.getBoundingClientRect().height,
      }
    })
  expect(tableCellLayout.actionDisplay).toBe('table-cell')
  expect(Math.abs(tableCellLayout.actionHeight - tableCellLayout.rowHeight)).toBeLessThanOrEqual(1)

  await page.setViewportSize({ width: 390, height: 844 })
  const toolbarBottoms = await page.locator('.transaction-month-toolbar').evaluate((toolbar) =>
    Array.from(toolbar.children)
      .slice(0, 3)
      .map((item) => {
        const rect = item.getBoundingClientRect()
        return rect.bottom
      }),
  )
  expect(Math.max(...toolbarBottoms) - Math.min(...toolbarBottoms)).toBeLessThanOrEqual(1)

  await page.setViewportSize({ width: 320, height: 844 })
  const mobileLayout = await page
    .locator('.entry-item')
    .first()
    .evaluate((entry) => {
      const head = entry.querySelector<HTMLElement>('.entry-head')
      const amount = entry.querySelector<HTMLElement>('.entry-amount')
      if (!head || !amount) {
        throw new Error('モバイル家計簿の見出しが見つかりません')
      }
      const headRect = head.getBoundingClientRect()
      const amountRect = amount.getBoundingClientRect()
      const overlaps =
        headRect.left < amountRect.right &&
        headRect.right > amountRect.left &&
        headRect.top < amountRect.bottom &&
        headRect.bottom > amountRect.top
      const currentScrollY = window.scrollY
      window.scrollTo(1_000_000, currentScrollY)
      const maxHorizontalScroll = window.scrollX
      window.scrollTo(0, currentScrollY)
      return {
        maxHorizontalScroll,
        overlaps,
      }
    })
  expect(mobileLayout.overlaps).toBeFalsy()
  expect(mobileLayout.maxHorizontalScroll).toBe(0)
})
