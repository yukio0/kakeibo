import { expect, test, type Locator, type Page } from '@playwright/test'
import { loginThroughMfa, resetE2eData, saveScreenshot } from './support/test-support'

type Master = {
  id: number
  name: string
}

type Transaction = {
  type: 'EXPENSE' | 'INCOME' | 'TRANSFER'
  date: string
  amount: number
  memo: string | null
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

async function getMasters(page: Page, path: string): Promise<Master[]> {
  const response = await page.request.get(path)
  expect(response.ok()).toBeTruthy()
  return (await response.json()) as Master[]
}

function idOf(masters: Master[], name: string): number {
  const master = masters.find((item) => item.name === name)
  if (!master) {
    throw new Error(`master not found: ${name}`)
  }
  return master.id
}

async function expectBottomAligned(first: Locator, second: Locator): Promise<void> {
  const [firstBox, secondBox] = await Promise.all([first.boundingBox(), second.boundingBox()])
  if (!firstBox || !secondBox) {
    throw new Error('比較対象の要素が表示されていません')
  }

  const firstBottom = firstBox.y + firstBox.height
  const secondBottom = secondBox.y + secondBox.height
  expect(Math.abs(firstBottom - secondBottom)).toBeLessThanOrEqual(1)
}

test('定期取引: 月操作リンクの文字と縦位置を揃える', async ({ page }) => {
  await page.getByRole('link', { name: '定期取引', exact: true }).click()

  const homeLink = page.getByRole('link', { name: '家計簿入力で確認', exact: true })
  const recurringMonthInput = page.getByLabel('対象月', { exact: true })
  await expect(homeLink).toBeVisible()
  expect(await homeLink.textContent()).toBe('家計簿入力で確認')
  await expectBottomAligned(recurringMonthInput, homeLink)

  await homeLink.click()

  const recurringLink = page.getByRole('link', { name: '定期取引から登録', exact: true })
  const transactionMonthInput = page.getByLabel('対象月', { exact: true })
  await expect(recurringLink).toBeVisible()
  expect(await recurringLink.textContent()).toBe('定期取引から登録')
  await expectBottomAligned(transactionMonthInput, recurringLink)
})

test('定期取引: 当月分を確認して既存取引を残したまま一括登録する', async ({ page }, testInfo) => {
  const headers = await csrfHeader(page)
  const [categories, paymentMethods, transferAccounts] = await Promise.all([
    getMasters(page, '/api/categories'),
    getMasters(page, '/api/payment-methods'),
    getMasters(page, '/api/transfer-accounts'),
  ])

  const templates = [
    {
      name: '家賃',
      enabled: true,
      dayOfMonth: 31,
      type: 'EXPENSE',
      categoryId: idOf(categories, '食費'),
      paymentMethodId: idOf(paymentMethods, '現金'),
      transferSourceId: null,
      transferDestinationId: null,
      defaultAmount: 80_000,
      memo: '定期取引E2E-家賃',
      displayOrder: 10,
    },
    {
      name: '給与',
      enabled: true,
      dayOfMonth: 25,
      type: 'INCOME',
      categoryId: idOf(categories, '給与'),
      paymentMethodId: null,
      transferSourceId: null,
      transferDestinationId: null,
      defaultAmount: 300_000,
      memo: '定期取引E2E-給与',
      displayOrder: 20,
    },
    {
      name: '貯金移動',
      enabled: true,
      dayOfMonth: 1,
      type: 'TRANSFER',
      categoryId: null,
      paymentMethodId: null,
      transferSourceId: idOf(transferAccounts, '財布'),
      transferDestinationId: idOf(transferAccounts, '銀行口座'),
      defaultAmount: 50_000,
      memo: '定期取引E2E-振替',
      displayOrder: 30,
    },
    {
      name: '光熱費',
      enabled: true,
      dayOfMonth: 10,
      type: 'EXPENSE',
      categoryId: idOf(categories, '食費'),
      paymentMethodId: idOf(paymentMethods, '現金'),
      transferSourceId: null,
      transferDestinationId: null,
      defaultAmount: null,
      memo: '定期取引E2E-光熱費',
      displayOrder: 40,
    },
  ] as const

  for (const template of templates) {
    const response = await page.request.post('/api/recurring-templates', {
      headers,
      data: template,
    })
    expect(response.status()).toBe(201)
  }

  await page.getByRole('link', { name: '定期取引', exact: true }).click()
  await expect(page.getByRole('heading', { name: '定期取引', exact: true })).toBeVisible()

  const templateTable = page.locator('.recurring-template-table')
  const rentName = templateTable.getByRole('textbox', { name: '家賃のテンプレート名' })
  const firstTemplateRow = templateTable.locator('tbody tr').first()
  const [displayOrderCell, actionCell] = [
    firstTemplateRow.locator('td').nth(8),
    firstTemplateRow.locator('td').nth(9),
  ]
  const [displayOrderBox, actionBox] = await Promise.all([
    displayOrderCell.boundingBox(),
    actionCell.boundingBox(),
  ])
  if (!displayOrderBox || !actionBox) {
    throw new Error('定期取引テンプレートの比較対象セルが表示されていません')
  }
  expect(displayOrderBox.x + displayOrderBox.width).toBeLessThanOrEqual(actionBox.x + 1)
  await expect(actionCell).toHaveCSS('position', 'static')

  await rentName.fill('家賃（編集中）')
  await templateTable
    .locator('tbody tr')
    .filter({ hasText: '給与' })
    .getByRole('button', { name: '更新', exact: true })
    .click()
  await expect(rentName).toHaveValue('家賃（編集中）')
  await rentName.fill('家賃')

  await page.getByLabel('対象月').fill('2026-02')
  await page.getByRole('button', { name: '登録内容を確認', exact: true }).click()

  const candidates = page.locator('.recurring-candidates-table')
  await expect(candidates).toBeVisible()
  await expect(page.getByRole('checkbox', { name: '家賃を登録' })).toBeChecked()

  const rentRow = candidates.locator('tbody tr').filter({ hasText: '家賃' })
  const rentDate = rentRow.locator('input[type="date"]')
  const rentCategory = rentRow.locator('td').nth(4).locator('select')
  await expect(rentDate).toHaveValue('2026-02-28')
  const [rentDateBox, rentCategoryBox] = await Promise.all([
    rentDate.boundingBox(),
    rentCategory.boundingBox(),
  ])
  if (!rentDateBox || !rentCategoryBox) {
    throw new Error('定期取引候補の比較対象入力欄が表示されていません')
  }
  expect(Math.abs(rentDateBox.y - rentCategoryBox.y)).toBeLessThanOrEqual(1)

  const utilityRow = candidates.locator('tbody tr').filter({ hasText: '光熱費' })
  await utilityRow.getByRole('spinbutton').fill('12345')
  await saveScreenshot(page, testInfo, 'recurring-candidates')

  await page.getByRole('button', { name: '選択した4件を登録', exact: true }).click()
  await expect(page.getByText('4件の定期取引を登録しました', { exact: true })).toBeVisible()
  await expect(page.getByRole('checkbox', { name: '家賃を登録' })).toBeDisabled()

  const transactionResponse = await page.request.get('/api/transactions?year=2026&month=2')
  expect(transactionResponse.ok()).toBeTruthy()
  const transactions = (await transactionResponse.json()) as Transaction[]
  expect(transactions).toHaveLength(4)
  expect(transactions).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        type: 'EXPENSE',
        date: '2026-02-28',
        amount: 80_000,
        memo: '定期取引E2E-家賃',
      }),
      expect.objectContaining({
        type: 'INCOME',
        amount: 300_000,
        memo: '定期取引E2E-給与',
      }),
      expect.objectContaining({
        type: 'TRANSFER',
        amount: 50_000,
        memo: '定期取引E2E-振替',
      }),
      expect.objectContaining({
        type: 'EXPENSE',
        amount: 12_345,
        memo: '定期取引E2E-光熱費',
      }),
    ]),
  )

  await page.getByRole('link', { name: /家計簿入力で確認/ }).click()
  await expect(page).toHaveURL(/month=2026-02/)
  await expect(page.getByText('登録済み 4件', { exact: true })).toBeVisible()
})

test('定期取引: 標準金額が空のテンプレートを画面から登録・更新・削除する', async ({ page }) => {
  await page.getByRole('link', { name: '定期取引', exact: true }).click()

  await page.getByLabel('テンプレート名', { exact: true }).fill('インターネット料金')
  await page.getByLabel('毎月の日', { exact: true }).fill('15')
  await expect(page.getByLabel('標準金額', { exact: true })).toHaveValue('')
  await page.getByRole('button', { name: '登録', exact: true }).click()
  await expect(
    page.getByText('定期取引テンプレート「インターネット料金」を登録しました', {
      exact: true,
    }),
  ).toBeVisible()

  const templateTable = page.locator('.recurring-template-table')
  const row = templateTable.locator('tbody tr').filter({ hasText: 'インターネット料金' })
  await expect(row).toHaveCount(1)
  await row.getByRole('spinbutton', { name: 'インターネット料金の標準金額' }).fill('6000')
  await row.getByRole('button', { name: '更新', exact: true }).click()
  await expect(
    page.getByText('定期取引テンプレート「インターネット料金」を更新しました', {
      exact: true,
    }),
  ).toBeVisible()

  page.once('dialog', (dialog) => dialog.accept())
  await row.getByRole('button', { name: '削除', exact: true }).click()
  await expect(row).toHaveCount(0)
})
