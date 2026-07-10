import { expect, test } from '@playwright/test'
import {
  currentLocalDate,
  findEditableTransactionRow,
  loginThroughMfa,
  resetE2eData,
  saveScreenshot,
} from './support/test-support'

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await page.context().clearCookies()
})

test('家計簿を1件入力して自動保存後に再表示する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)
  await saveScreenshot(page, testInfo, 'transaction-empty')

  const row = findEditableTransactionRow(page)
  await row.locator('input[type="date"]').fill(currentLocalDate())
  await row.locator('select').nth(0).selectOption('EXPENSE')
  await row.locator('select').nth(1).selectOption({ label: '食費' })
  await row.locator('select').nth(2).selectOption({ label: '現金' })

  const saveResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/transactions/monthly') &&
      response.request().method() === 'PUT' &&
      response.ok(),
  )
  await row.locator('input[type="number"]').fill('1234')
  await row.locator('textarea').fill('E2Eテストの支出')
  await saveResponse

  await expect(page.getByText('登録済み 1件', { exact: true })).toBeVisible()
  await saveScreenshot(page, testInfo, 'transaction-saved')

  await page.reload()
  const savedRow = findEditableTransactionRow(page)
  await expect(savedRow.locator('textarea')).toHaveValue('E2Eテストの支出')
  await expect(savedRow.locator('input[type="number"]')).toHaveValue('1234')
  await saveScreenshot(page, testInfo, 'transaction-reloaded')
})

test('金額が未入力の行では金額セルにエラーを表示する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)

  const row = findEditableTransactionRow(page)
  await row.locator('input[type="number"]').fill('0')

  const amountInput = row.locator('input[type="number"]')
  await expect(amountInput).toHaveClass(/cell-error/)
  await expect(row.locator('.field-error')).toBeVisible()
  await saveScreenshot(page, testInfo, 'transaction-validation-error')
})
