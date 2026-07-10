export type ApiFieldError = {
  field: string
  message: string
}

type ApiErrorBody = {
  message?: string
  errors?: ApiFieldError[]
}

export class ApiError extends Error {
  readonly status: number
  readonly errors: ApiFieldError[]

  constructor(status: number, message: string, errors: ApiFieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.errors = errors
  }
}

const jsonContentType = 'application/json'

type RequestOptions = Omit<RequestInit, 'body'> & {
  body?: unknown
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, ...requestOptions } = options
  const headers = new Headers(requestOptions.headers)
  const init: RequestInit = {
    ...requestOptions,
    headers,
  }

  if (body !== undefined) {
    headers.set('Content-Type', jsonContentType)
    init.body = JSON.stringify(body)
  }

  const response = await fetch(path, init)
  const contentType = response.headers.get('content-type') ?? ''

  if (!response.ok) {
    const body = contentType.includes(jsonContentType)
      ? ((await response.json()) as ApiErrorBody)
      : undefined

    throw new ApiError(
      response.status,
      body?.message ?? `API request failed: ${response.status}`,
      body?.errors ?? [],
    )
  }

  if (response.status === 204) {
    return undefined as T
  }

  if (contentType.includes(jsonContentType)) {
    return (await response.json()) as T
  }

  return (await response.text()) as T
}
