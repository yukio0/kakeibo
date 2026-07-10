export function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

export function formatDate(value: Date): string {
  return [value.getFullYear(), pad2(value.getMonth() + 1), pad2(value.getDate())].join('-')
}

export function isValidDateString(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) {
    return false
  }

  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(year, month - 1, day)
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day
}
