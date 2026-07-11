import { computed, ref, type Ref } from 'vue'
import {
  applySnapshot,
  createEmptyRow,
  EDITABLE_FIELDS,
  isBlankNewRow,
  snapshotRow,
  type EditableField,
  type RowDefaults,
  type RowEditSnapshot,
  type SaveEntry,
  type TransactionFieldErrors,
  type TransactionRow,
} from '@/transactions/rowModel'

type ActiveCell = {
  rowKey: string
  field: EditableField
}

/** 保存でDOMが差し替わったあと、元のセルへ戻るための目印。 */
type FocusRestoreTarget = {
  field: EditableField
  entryIndex?: number
  blankRow?: boolean
}

export type CellNavigationOptions = {
  rows: Ref<TransactionRow[]>
  isFieldDisabled: (row: TransactionRow, field: EditableField) => boolean
  rowDefaults: () => RowDefaults
  /** Escape で行を戻したときに呼ぶ。 */
  onRowRestored: (row: TransactionRow) => void
}

export function useCellNavigation(options: CellNavigationOptions) {
  const activeCell = ref<ActiveCell | null>(null)
  const rowEditSnapshot = ref<RowEditSnapshot | null>(null)

  const activeRow = computed(() => {
    const current = activeCell.value
    if (!current) {
      return null
    }
    return options.rows.value.find((row) => row.localKey === current.rowKey) ?? null
  })

  function startCellEdit(row: TransactionRow, field: EditableField): void {
    activeCell.value = { rowKey: row.localKey, field }
    rowEditSnapshot.value = snapshotRow(row)
  }

  function isActiveCell(row: TransactionRow, field: EditableField): boolean {
    return activeCell.value?.rowKey === row.localKey && activeCell.value.field === field
  }

  function handleCellKeydown(
    event: KeyboardEvent,
    row: TransactionRow,
    field: EditableField,
  ): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      const snapshot = rowEditSnapshot.value
      if (snapshot) {
        applySnapshot(row, snapshot)
      }
      options.onRowRestored(row)
      return
    }

    if (event.key === 'Tab') {
      event.preventDefault()
      focusAdjacentCell(row, field, event.shiftKey ? -1 : 1)
    }
  }

  function focusCell(rowKey: string, field: EditableField): void {
    window.requestAnimationFrame(() => {
      document
        .querySelector<HTMLElement>(`[data-cell-key="${rowKey}"][data-cell-field="${field}"]`)
        ?.focus()
    })
  }

  function focusAdjacentCell(row: TransactionRow, field: EditableField, direction: 1 | -1): void {
    const editableRows = options.rows.value.filter((current) => !current.deleted)
    const rowIndex = editableRows.findIndex((current) => current.localKey === row.localKey)
    const fieldIndex = EDITABLE_FIELDS.indexOf(field)
    if (rowIndex === -1 || fieldIndex === -1) {
      return
    }

    let nextRowIndex = rowIndex
    let nextFieldIndex = fieldIndex
    const maxAttempts = (editableRows.length + 1) * EDITABLE_FIELDS.length

    for (let attempts = 0; attempts < maxAttempts; attempts += 1) {
      nextFieldIndex += direction

      if (nextFieldIndex >= EDITABLE_FIELDS.length) {
        nextFieldIndex = 0
        nextRowIndex += 1
      } else if (nextFieldIndex < 0) {
        nextFieldIndex = EDITABLE_FIELDS.length - 1
        nextRowIndex -= 1
      }

      if (nextRowIndex < 0) {
        return
      }

      if (nextRowIndex >= editableRows.length) {
        const lastRow = editableRows[editableRows.length - 1]
        if (!lastRow || lastRow.id === null) {
          return
        }

        const newRow = createEmptyRow(options.rowDefaults())
        options.rows.value = [...options.rows.value, newRow]
        editableRows.push(newRow)
      }

      const nextRow = editableRows[nextRowIndex]
      const nextField = EDITABLE_FIELDS[nextFieldIndex]
      if (!options.isFieldDisabled(nextRow, nextField)) {
        focusCell(nextRow.localKey, nextField)
        return
      }
    }
  }

  function focusFirstError(
    entries: SaveEntry[],
    rowErrors: Record<string, TransactionFieldErrors>,
  ): void {
    const firstError = entries
      .map((entry) => {
        const field = EDITABLE_FIELDS.find(
          (candidate) => rowErrors[entry.row.localKey]?.[candidate],
        )
        return field ? { row: entry.row, field } : null
      })
      .find((error) => error !== null)

    if (firstError) {
      focusCell(firstError.row.localKey, firstError.field)
    }
  }

  function createFocusRestoreTarget(entries: SaveEntry[]): FocusRestoreTarget | null {
    const current = activeCell.value
    if (!current) {
      return null
    }

    const entryIndex = entries.findIndex((entry) => entry.row.localKey === current.rowKey)
    if (entryIndex !== -1) {
      return { field: current.field, entryIndex }
    }

    const row = options.rows.value.find((currentRow) => currentRow.localKey === current.rowKey)
    if (row && isBlankNewRow(row)) {
      return { field: current.field, blankRow: true }
    }

    return null
  }

  function restoreFocus(target: FocusRestoreTarget | null): void {
    if (!target) {
      return
    }

    const row = target.blankRow
      ? options.rows.value.find((current) => current.id === null && isBlankNewRow(current))
      : target.entryIndex === undefined
        ? undefined
        : options.rows.value[target.entryIndex]

    if (row && !options.isFieldDisabled(row, target.field)) {
      focusCell(row.localKey, target.field)
    }
  }

  return {
    activeRow,
    startCellEdit,
    isActiveCell,
    handleCellKeydown,
    focusCell,
    focusFirstError,
    createFocusRestoreTarget,
    restoreFocus,
  }
}
