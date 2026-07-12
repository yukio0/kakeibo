import { expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test'
import { E2E_USER } from './credentials'
import { generateTotp } from './totp'

export async function resetE2eData(request: APIRequestContext): Promise<void> {
  const csrfResponse = await request.get('/api/csrf')
  expect(csrfResponse.ok()).toBeTruthy()

  const csrf = (await csrfResponse.json()) as {
    headerName: string
    token: string
  }
  const resetResponse = await request.post('/api/e2e/reset', {
    headers: {
      [csrf.headerName]: csrf.token,
    },
  })
  expect(resetResponse.status()).toBe(204)
}

export async function loginThroughMfa(page: Page, trustDevice = false): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('ユーザー名').fill(E2E_USER.username)
  await page.getByLabel('パスワード').fill(E2E_USER.password)
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await expect(page).toHaveURL(/\/mfa\/verify/)

  if (trustDevice) {
    await page.getByLabel('この端末では30日間2段階認証を省略する').check()
  }

  await page.getByLabel('確認コード').fill(generateTotp(E2E_USER.totpSecret))
  await page.getByRole('button', { name: '確認する', exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '家計簿入力', exact: true })).toBeVisible()
}

export async function saveScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const path = testInfo.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: true })
  await testInfo.attach(name, {
    path,
    contentType: 'image/png',
  })
}

export function findNewTransactionRow(page: Page) {
  return page.locator('.transaction-table tbody tr.new-transaction-row')
}

export function findEditableTransactionRow(page: Page) {
  return page
    .locator('.transaction-table tbody tr:not(.new-transaction-row)')
    .filter({
      has: page.locator('input[type="number"]'),
    })
    .first()
}

export function currentLocalDate(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
