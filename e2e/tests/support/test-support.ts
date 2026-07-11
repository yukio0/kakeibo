import {
  expect,
  type APIRequestContext,
  type Page,
  type Response,
  type TestInfo,
} from '@playwright/test'
import { E2E_USER } from './credentials'
import { generateTotp } from './totp'

export type MonthlySaveRow = {
  id: number | null
  date: string | null
  type: string | null
  categoryId: number | null
  paymentMethodId: number | null
  amount: number | null
  memo: string | null
  displayOrder: number
}

/**
 * 家計簿の自動保存は入力のたびに700msのデバウンスで走る。
 * セルを続けて埋めるとデバウンスが途中で発火して、最後の入力を含まないPUTが先に飛ぶ。
 * 「PUTが成功した」だけを待つと、その先行PUTで解決してしまい、
 * 直後のリロードで最後の入力が消えたように見える。送信ボディが期待した内容のPUTだけを待つこと。
 */
export function waitForMonthlySave(
  page: Page,
  matches: (rows: MonthlySaveRow[]) => boolean,
): Promise<Response> {
  return page.waitForResponse((response) => {
    if (!response.url().includes('/api/transactions/monthly')) {
      return false
    }
    if (response.request().method() !== 'PUT' || !response.ok()) {
      return false
    }

    return matches(response.request().postDataJSON() as MonthlySaveRow[])
  })
}

/**
 * 新規行を含む保存の後処理が落ち着くまで待つ。アプリはPUT成功後に月次集計を取り直し、その後
 * requestAnimationFrame で最後に編集したセルへフォーカスを戻す。この非同期のフォーカス復帰が
 * 次のセル入力と競合すると文字が別セルに入るため、集計取得(GET)の完了とrAF2枚の消化を待って
 * 復帰を確定させてから次の操作へ進む。
 *
 * PUTを起こす操作より前に呼び、返り値をPUT待ちの後に await すること
 * (呼んだ時点で集計GETの待受が始まるので、応答を取りこぼさない)。
 */
export function settleAfterMonthlySave(page: Page): Promise<void> {
  const summaryReceived = page.waitForResponse(
    (response) =>
      response.url().includes('/api/summary/monthly') &&
      response.request().method() === 'GET' &&
      response.ok(),
  )

  return summaryReceived.then(() =>
    page.evaluate(
      () =>
        new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
        }),
    ),
  )
}

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

export function findEditableTransactionRow(page: Page) {
  return page
    .locator('.transaction-table tbody tr')
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
