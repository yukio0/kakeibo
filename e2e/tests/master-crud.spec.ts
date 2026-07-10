import { expect, test, type Locator, type Page } from '@playwright/test'
import { loginThroughMfa, resetE2eData } from './support/test-support'

test.beforeEach(async ({ page, request }) => {
  await resetE2eData(request)
  await loginThroughMfa(page)
})

async function findRow(page: Page, name: string): Promise<Locator> {
  const rows = page.locator('tbody tr')
  const count = await rows.count()
  for (let index = 0; index < count; index += 1) {
    const row = rows.nth(index)
    const nameInput = row.locator('td.name-cell input')
    if ((await nameInput.count()) > 0 && (await nameInput.inputValue()) === name) {
      return row
    }
  }
  throw new Error(`row not found: ${name}`)
}

function nameValuesIn(scope: Page | Locator): Promise<string[]> {
  return scope
    .locator('tbody tr td.name-cell input')
    .evaluateAll((inputs) => inputs.map((input) => (input as HTMLInputElement).value))
}

const createForm = (page: Page) => page.locator('.status-card form')

test('支払い方法を登録・自動保存編集・削除できる', async ({ page }) => {
  await page.goto('/payment-methods')
  await expect(page.getByRole('heading', { name: '支払い方法管理' })).toBeVisible()

  // 表示順が既存の最大(20)+10で自動採番される
  await expect(createForm(page).locator('input[type="number"]')).toHaveValue('30')

  await createForm(page).locator('input[type="text"]').fill('テスト決済')
  await page.getByRole('button', { name: '登録', exact: true }).click()
  await expect(page.locator('.message.success')).toHaveText('支払い方法を登録しました')

  // 登録後は名前欄がクリアされ、表示順は次の値に繰り上がる
  await expect(createForm(page).locator('input[type="text"]')).toHaveValue('')
  await expect(createForm(page).locator('input[type="number"]')).toHaveValue('40')

  // 行編集のデバウンス自動保存
  const row = await findRow(page, 'テスト決済')
  await row.locator('td.name-cell input').fill('テスト決済改')
  await page.waitForTimeout(1500)
  await page.reload()
  await expect.poll(() => nameValuesIn(page)).toContain('テスト決済改')

  // 重複名は一覧エラーとフィールドエラーの両方に出る
  await createForm(page).locator('input[type="text"]').fill('テスト決済改')
  await page.getByRole('button', { name: '登録', exact: true }).click()
  await expect(page.locator('.message.error')).toHaveText('同じ支払い方法名はすでに存在します')
  await expect(createForm(page).locator('.field-error')).toHaveText(
    '同じ支払い方法名はすでに存在します',
  )

  // 削除（確認ダイアログの文面も検証）
  let dialogMessage = ''
  page.once('dialog', (dialog) => {
    dialogMessage = dialog.message()
    void dialog.accept()
  })
  const target = await findRow(page, 'テスト決済改')
  await target.getByRole('button', { name: '削除', exact: true }).click()
  await expect(page.locator('.message.success')).toHaveText('支払い方法を削除しました')
  expect(dialogMessage).toBe('支払い方法「テスト決済改」を削除します。よろしいですか？')
  expect(await nameValuesIn(page)).not.toContain('テスト決済改')
})

test('振替元・振替先を登録して削除できる', async ({ page }) => {
  await page.goto('/transfers')
  await expect(page.getByRole('heading', { name: '振替管理' })).toBeVisible()

  await createForm(page).locator('input[type="text"]').fill('証券口座')
  await page.getByRole('button', { name: '登録', exact: true }).click()
  await expect(page.locator('.message.success')).toHaveText('振替元・振替先を登録しました')
  expect(await nameValuesIn(page)).toContain('証券口座')

  let dialogMessage = ''
  page.once('dialog', (dialog) => {
    dialogMessage = dialog.message()
    void dialog.accept()
  })
  const target = await findRow(page, '証券口座')
  await target.getByRole('button', { name: '削除', exact: true }).click()
  await expect(page.locator('.message.success')).toHaveText('振替元・振替先を削除しました')
  expect(dialogMessage).toBe('振替元・振替先「証券口座」を削除します。よろしいですか？')
})

test('カテゴリは種別ごとに表示順を採番し、登録後も選んだ種別を保つ', async ({ page }) => {
  await page.goto('/categories')
  await expect(page.getByRole('heading', { name: 'カテゴリ管理' })).toBeVisible()

  const typeSelect = createForm(page).locator('select')
  const displayOrder = createForm(page).locator('input[type="number"]')

  // 種別ごとに 食費(10) / 給与(10) だけなので、どちらも次は20
  await expect(displayOrder).toHaveValue('20')
  await typeSelect.selectOption('INCOME')
  await expect(displayOrder).toHaveValue('20')

  await createForm(page).locator('input[type="text"]').fill('副業収入')
  await page.getByRole('button', { name: '登録', exact: true }).click()
  await expect(page.locator('.message.success')).toHaveText('カテゴリを登録しました')

  // 登録しても種別の選択は収入のまま。表示順は収入側だけが繰り上がる
  await expect(typeSelect).toHaveValue('INCOME')
  await expect(displayOrder).toHaveValue('30')
  await typeSelect.selectOption('EXPENSE')
  await expect(displayOrder).toHaveValue('20')
  await typeSelect.selectOption('INCOME')

  // 収入セクション側に並ぶ
  const incomeSection = page.locator('section.category-section').nth(1)
  await expect.poll(() => nameValuesIn(incomeSection)).toContain('副業収入')

  // 行の種別を支出に変えると自動保存され、支出セクションへ移る
  const row = await findRow(page, '副業収入')
  await row.locator('td.type-cell select').selectOption('EXPENSE')
  await page.waitForTimeout(1500)
  await page.reload()

  const expenseSection = page.locator('section.category-section').nth(0)
  await expect.poll(() => nameValuesIn(expenseSection)).toContain('副業収入')
  await expect.poll(() => nameValuesIn(incomeSection)).not.toContain('副業収入')
})
