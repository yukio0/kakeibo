import { expect, test } from '@playwright/test'
import {
  loginThroughMfa,
  resetE2eData,
  saveElementScreenshot,
  saveScreenshot,
} from './support/test-support'

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await loginThroughMfa(page)
})

test('2FA設定: 有効化を開始するとQRコードと手動入力用secretを表示する', async ({
  page,
}, testInfo) => {
  await page.getByRole('link', { name: '2FA設定', exact: true }).click()

  // E2Eユーザーは2FA有効で始まる。QRは無効のときだけ出るので、いったん無効にする。
  await page.getByRole('button', { name: '無効にする', exact: true }).click()
  await expect(page.getByText('2段階認証を無効にしました')).toBeVisible()

  await page.getByRole('button', { name: '2段階認証を有効化する', exact: true }).click()

  const qrCode = page.locator('img.mfa-qr-code')
  await expect(qrCode).toBeVisible()
  await saveScreenshot(page, testInfo, 'mfa-setup-qr')
  // QRは画面全体のキャプチャでは小さいので、読み取れる大きさで単体でも残す。
  await saveElementScreenshot(qrCode, testInfo, 'mfa-setup-qr-code')

  const image = await qrCode.evaluate((element: HTMLImageElement) => ({
    loaded: element.complete && element.naturalWidth > 0,
    sourceLength: element.src.length,
  }))
  // 画像として読み込めていること。データURIが壊れると complete のまま naturalWidth が0になる。
  expect(image.loaded).toBeTruthy()
  // モジュール単位で描いている限りこの範囲に収まる。ピクセル単位に戻ると一桁増える。
  expect(image.sourceLength).toBeLessThan(100_000)

  // 認証アプリを使えない場合に備えて、secretも読み取れること。
  await expect(page.getByLabel('手動入力用secret')).toHaveValue(/^[A-Z2-7]{16,}$/)
})
