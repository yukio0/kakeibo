import { expect, test, type Page } from '@playwright/test'
import {
  currentLocalDate,
  findEditableTransactionRow,
  loginThroughMfa,
  resetE2eData,
  saveScreenshot,
  settleAfterMonthlySave,
  waitForMonthlySave,
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

  const saveResponse = waitForMonthlySave(
    page,
    (rows) => rows.length === 1 && rows[0].amount === 1234 && rows[0].memo === 'E2Eテストの支出',
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

  const row = findEditableTransactionRow(page)
  await row.locator('input[type="number"]').fill('0')

  const amountInput = row.locator('input[type="number"]')
  await expect(amountInput).toHaveClass(/cell-error/)
  await expect(row.locator('.field-error')).toBeVisible()
  await saveScreenshot(page, testInfo, 'transaction-validation-error')
})

const enteredRows = (page: Page) =>
  page.locator('.transaction-table tbody tr').filter({ has: page.locator('input[type="number"]') })

function amountValues(page: Page): Promise<string[]> {
  return page
    .locator('.transaction-table tbody tr input[type="number"]')
    .evaluateAll((inputs) => inputs.map((input) => (input as HTMLInputElement).value))
}

test('同じ内容の行を2件保存しても、片方の更新で行が重複しない', async ({ page }) => {
  await loginThroughMfa(page)
  const date = currentLocalDate()

  const first = enteredRows(page).nth(0)
  await first.locator('input[type="date"]').fill(date)
  await first.locator('select').nth(1).selectOption({ label: '食費' })
  await first.locator('select').nth(2).selectOption({ label: '現金' })
  let saved = waitForMonthlySave(
    page,
    (rows) => rows.length === 1 && rows[0].amount === 1000 && rows[0].memo === '同一内容',
  )
  let settled = settleAfterMonthlySave(page)
  await first.locator('input[type="number"]').fill('1000')
  await first.locator('textarea').fill('同一内容')
  await saved
  await settled

  // 2行目に1行目と完全に同じ内容を入力する
  const second = enteredRows(page).nth(1)
  await second.locator('input[type="date"]').fill(date)
  await second.locator('select').nth(1).selectOption({ label: '食費' })
  await second.locator('select').nth(2).selectOption({ label: '現金' })
  saved = waitForMonthlySave(
    page,
    (rows) =>
      rows.length === 2 && rows.every((row) => row.amount === 1000 && row.memo === '同一内容'),
  )
  settled = settleAfterMonthlySave(page)
  await second.locator('input[type="number"]').fill('1000')
  await second.locator('textarea').fill('同一内容')
  await saved
  await settled

  await expect(page.getByText('登録済み 2件', { exact: true })).toBeVisible()

  // 1行目だけ金額を変える。保存後のIDが行に正しく戻っていなければ3件目が生まれる
  saved = waitForMonthlySave(
    page,
    (rows) => rows.length === 2 && rows[0].amount === 2000 && rows[1].amount === 1000,
  )
  await enteredRows(page).nth(0).locator('input[type="number"]').fill('2000')
  await saved

  await page.reload()
  await expect(page.getByText('登録済み 2件', { exact: true })).toBeVisible()
  await expect.poll(() => amountValues(page)).toEqual(['2000', '1000', ''])
})
