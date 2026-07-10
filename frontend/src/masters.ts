import type { Category } from '@/api/kakeibo'

export type MasterItem = {
  id: number
  name: string
  displayOrder: number
}

export type MasterForm = {
  name: string
  displayOrder: number
}

export function compareByDisplayOrder(left: MasterItem, right: MasterItem): number {
  const displayOrder = left.displayOrder - right.displayOrder
  if (displayOrder !== 0) {
    return displayOrder
  }

  return left.id - right.id
}

export function compareCategories(left: Category, right: Category): number {
  const typeOrder = left.type.localeCompare(right.type)
  if (typeOrder !== 0) {
    return typeOrder
  }

  return compareByDisplayOrder(left, right)
}

export function nextDisplayOrderOf(items: readonly MasterItem[]): number {
  return Math.max(-10, ...items.map((item) => item.displayOrder)) + 10
}
