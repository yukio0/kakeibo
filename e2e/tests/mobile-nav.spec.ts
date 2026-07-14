import { expect, test } from '@playwright/test'
import { loginThroughMfa, resetE2eData, saveScreenshot } from './support/test-support'

test.use({ viewport: { width: 375, height: 812 } })

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await page.context().clearCookies()
})

test('モバイル: ハンバーガーでナビを開閉し、リンクで遷移する', async ({ page }, testInfo) => {
  await loginThroughMfa(page)

  const toggle = page.getByRole('button', { name: 'メニュー' })
  await expect(toggle).toBeVisible()

  // ドロワーは閉じており、ナビのリンクは非表示
  const summaryLink = page.getByRole('link', { name: '集計', exact: true })
  await expect(summaryLink).toBeHidden()

  // 開く → リンクが見える
  await toggle.click()
  await expect(summaryLink).toBeVisible()
  await saveScreenshot(page, testInfo, 'mobile-nav-drawer')

  // リンクをタップすると遷移し、ドロワーは自動で閉じる
  await summaryLink.click()
  await expect(page).toHaveURL(/\/summary$/)
  await expect(page.getByRole('heading', { name: '集計', exact: true })).toBeVisible()
  await expect(summaryLink).toBeHidden()
})
