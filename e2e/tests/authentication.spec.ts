import { expect, test } from '@playwright/test'
import { E2E_USER } from './support/credentials'
import { loginThroughMfa, resetE2eData, saveScreenshot } from './support/test-support'
import { generateTotp } from './support/totp'

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await page.context().clearCookies()
})

test('ログイン失敗時にエラーを表示する', async ({ page }, testInfo) => {
  await page.goto('/login')
  await saveScreenshot(page, testInfo, 'login')

  await page.getByLabel('ユーザー名').fill(E2E_USER.username)
  await page.getByLabel('パスワード').fill('invalid-password')
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()

  await expect(page.getByText('ユーザー名またはパスワードが正しくありません')).toBeVisible()
  await saveScreenshot(page, testInfo, 'login-error')
})

test('2FAを完了して家計簿画面を表示する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)

  await expect(page.getByText('登録済み 0件', { exact: true })).toBeVisible()
  await saveScreenshot(page, testInfo, 'authenticated-home')
})

test('信頼済み端末では2FA入力を省略する', async ({ page }, testInfo) => {
  await page.goto('/login')
  await page.getByLabel('ユーザー名').fill(E2E_USER.username)
  await page.getByLabel('パスワード').fill(E2E_USER.password)
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  await expect(page).toHaveURL(/\/mfa\/verify/)

  await page.getByLabel('この端末では30日間2段階認証を省略する').check()
  await saveScreenshot(page, testInfo, 'mfa-trusted-device')

  await page.getByLabel('確認コード').fill('000000')
  await page.getByRole('button', { name: '確認する', exact: true }).click()
  await expect(page.getByText('確認コードが正しくありません')).toBeVisible()

  await page.getByLabel('確認コード').fill(generateTotp(E2E_USER.totpSecret))
  await page.getByRole('button', { name: '確認する', exact: true }).click()
  await expect(page.getByRole('heading', { name: '家計簿入力', exact: true })).toBeVisible()
  await saveScreenshot(page, testInfo, 'trusted-device-login-complete')

  await page.getByRole('link', { name: '信頼済み端末', exact: true }).click()
  await expect(page.locator('.trusted-device-table')).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  const trustedDeviceLayout = await page.locator('.trusted-device-table').evaluate((table) => {
    const wrapper = table.parentElement
    if (!wrapper) {
      throw new Error('信頼済み端末テーブルのラッパーが見つかりません')
    }
    return {
      bodyOverflows: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      wrapperScrolls: wrapper.scrollWidth > wrapper.clientWidth,
    }
  })
  expect(trustedDeviceLayout.bodyOverflows).toBeFalsy()
  expect(trustedDeviceLayout.wrapperScrolls).toBeTruthy()

  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/')

  await page.getByRole('button', { name: 'ログアウト', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)

  await page.getByLabel('ユーザー名').fill(E2E_USER.username)
  await page.getByLabel('パスワード').fill(E2E_USER.password)
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '家計簿入力', exact: true })).toBeVisible()
  await saveScreenshot(page, testInfo, 'trusted-device-mfa-skipped')
})
