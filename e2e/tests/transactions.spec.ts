import { expect, test, type Page } from '@playwright/test'
import {
  currentLocalDate,
  findEditableTransactionRow,
  findNewTransactionRow,
  loginThroughMfa,
  resetE2eData,
  saveScreenshot,
} from './support/test-support'

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await page.context().clearCookies()
})

test('家計簿を1件登録して再表示する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)
  await saveScreenshot(page, testInfo, 'transaction-empty')

  const row = findNewTransactionRow(page)
  await row.locator('input[type="date"]').fill(currentLocalDate())
  await row.locator('select').nth(0).selectOption('EXPENSE')
  await row.locator('select').nth(1).selectOption({ label: '食費' })
  await row.locator('select').nth(2).selectOption({ label: '現金' })

  const saveResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/transactions?') &&
      response.request().method() === 'POST' &&
      response.ok(),
  )
  await row.locator('input[type="number"]').fill('1234')
  await row.locator('textarea').fill('E2Eテストの支出')
  await row.getByRole('button', { name: '登録', exact: true }).click()
  await saveResponse

  await expect(page.getByText('登録済み 1件', { exact: true })).toBeVisible()
  await saveScreenshot(page, testInfo, 'transaction-saved')

  await page.reload()
  const savedRow = findEditableTransactionRow(page)
  await expect(savedRow.locator('textarea')).toHaveValue('E2Eテストの支出')
  await expect(savedRow.locator('input[type="number"]')).toHaveValue('1234')
  await saveScreenshot(page, testInfo, 'transaction-reloaded')

  await page.getByRole('link', { name: 'CSV出力', exact: true }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '累積のCSVを出力', exact: true }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('kakeibo-all.csv')
})

test('CSV出力でデータがない場合はファイルを出力しない', async ({ page }) => {
  await loginThroughMfa(page)

  await page.getByRole('link', { name: 'CSV出力', exact: true }).click()
  await page.getByRole('button', { name: '累積のCSVを出力', exact: true }).click()

  await expect(page.getByText('期間内にデータがありません', { exact: true })).toBeVisible()
})

test('金額が未入力の行では金額セルにエラーを表示する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)

  const row = findNewTransactionRow(page)
  await row.locator('input[type="number"]').fill('0')
  await row.getByRole('button', { name: '登録', exact: true }).click()

  const amountInput = row.locator('input[type="number"]')
  await expect(amountInput).toHaveClass(/cell-error/)
  await expect(row.locator('.field-error')).toBeVisible()
  await saveScreenshot(page, testInfo, 'transaction-validation-error')
})

const enteredRows = (page: Page) =>
  page
    .locator('.transaction-table tbody tr:not(.new-transaction-row)')
    .filter({ has: page.locator('input[type="number"]') })

function amountValues(page: Page): Promise<string[]> {
  return page
    .locator('.transaction-table tbody tr:not(.new-transaction-row) input[type="number"]')
    .evaluateAll((inputs) => inputs.map((input) => (input as HTMLInputElement).value))
}

test('同じ内容の行を2件保存しても、片方の更新で行が重複しない', async ({ page }) => {
  await loginThroughMfa(page)
  const date = currentLocalDate()

  const first = findNewTransactionRow(page)
  await first.locator('input[type="date"]').fill(date)
  await first.locator('select').nth(1).selectOption({ label: '食費' })
  await first.locator('select').nth(2).selectOption({ label: '現金' })
  let saved = page.waitForResponse(
    (response) =>
      response.url().includes('/api/transactions?') &&
      response.request().method() === 'POST' &&
      response.ok(),
  )
  await first.locator('input[type="number"]').fill('1000')
  await first.locator('textarea').fill('同一内容')
  await first.getByRole('button', { name: '登録', exact: true }).click()
  await saved

  // 新規入力行に1行目と完全に同じ内容を入力する
  const second = findNewTransactionRow(page)
  await second.locator('input[type="date"]').fill(date)
  await second.locator('select').nth(1).selectOption({ label: '食費' })
  await second.locator('select').nth(2).selectOption({ label: '現金' })
  saved = page.waitForResponse(
    (response) =>
      response.url().includes('/api/transactions?') &&
      response.request().method() === 'POST' &&
      response.ok(),
  )
  await second.locator('input[type="number"]').fill('1000')
  await second.locator('textarea').fill('同一内容')
  await second.getByRole('button', { name: '登録', exact: true }).click()
  await saved

  await expect(page.getByText('登録済み 2件', { exact: true })).toBeVisible()

  // 1行目だけ金額を変える。保存後のIDが行に正しく戻っていなければ3件目が生まれる
  saved = page.waitForResponse(
    (response) =>
      response.url().includes('/api/transactions/') &&
      response.request().method() === 'PUT' &&
      response.ok(),
  )
  await enteredRows(page).nth(0).locator('input[type="number"]').fill('2000')
  await enteredRows(page).nth(0).getByRole('button', { name: '更新', exact: true }).click()
  await saved

  await page.reload()
  await expect(page.getByText('登録済み 2件', { exact: true })).toBeVisible()
  await expect.poll(() => amountValues(page)).toEqual(['2000', '1000'])
})

test('登録済みの行を金額で昇順・降順に並び替えられる', async ({ page }) => {
  await loginThroughMfa(page)
  const date = currentLocalDate()

  for (const [amount, memo] of [
    ['2000', '高い金額'],
    ['1000', '低い金額'],
  ]) {
    const row = findNewTransactionRow(page)
    await row.locator('input[type="date"]').fill(date)
    await row.locator('select').nth(1).selectOption({ label: '食費' })
    await row.locator('select').nth(2).selectOption({ label: '現金' })
    const created = page.waitForResponse(
      (response) =>
        response.url().includes('/api/transactions?') &&
        response.request().method() === 'POST' &&
        response.ok(),
    )
    await row.locator('input[type="number"]').fill(amount)
    await row.locator('textarea').fill(memo)
    await row.getByRole('button', { name: '登録', exact: true }).click()
    await created
  }

  await expect.poll(() => amountValues(page)).toEqual(['2000', '1000'])

  await page.getByRole('button', { name: '金額を昇順で並び替え' }).click()
  await expect.poll(() => amountValues(page)).toEqual(['1000', '2000'])

  await page.getByRole('button', { name: '金額を降順で並び替え' }).click()
  await expect.poll(() => amountValues(page)).toEqual(['2000', '1000'])
})
