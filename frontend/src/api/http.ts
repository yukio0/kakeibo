export type ApiFieldError = {
  field: string
  message: string
}

type ApiErrorBody = {
  message?: string
  errors?: ApiFieldError[]
}

export type CsrfToken = {
  headerName: string
  parameterName: string
  token: string
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
const csrfPath = '/api/csrf'

type RequestOptions = Omit<RequestInit, 'body'> & {
  body?: unknown
  /** レスポンスボディにメッセージが無いときに ApiError へ載せる文言。 */
  fallbackMessage?: string
}

let csrfToken: CsrfToken | null = null
let csrfTokenPromise: Promise<CsrfToken> | null = null

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await sendRequest(path, options)

  if (response.status === 204) {
    return undefined as T
  }

  if (isJson(response)) {
    return (await response.json()) as T
  }

  return (await response.text()) as T
}

export async function apiBlobRequest(
  path: string,
  options: RequestOptions = {},
): Promise<Blob | null> {
  const response = await sendRequest(path, options)
  if (response.status === 204) {
    return null
  }
  return response.blob()
}

export function toMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  return '通信に失敗しました'
}

/** CSRFトークンの付与とエラーレスポンスの解釈をまとめる。ボディの読み方だけが呼び出し側で変わる。 */
async function sendRequest(path: string, options: RequestOptions): Promise<Response> {
  const { body, fallbackMessage, ...requestOptions } = options
  const headers = new Headers(requestOptions.headers)
  const method = (requestOptions.method ?? 'GET').toUpperCase()
  const init: RequestInit = {
    ...requestOptions,
    headers,
    credentials: requestOptions.credentials ?? 'same-origin',
  }

  if (body !== undefined) {
    if (body instanceof FormData) {
      // multipart のバウンダリはブラウザに設定させるため Content-Type は付けない。
      init.body = body
    } else {
      headers.set('Content-Type', jsonContentType)
      init.body = JSON.stringify(body)
    }
  }

  if (isUnsafeMethod(method) && path !== csrfPath) {
    const token = await getCsrfToken()
    headers.set(token.headerName, token.token)
  }

  const response = await fetch(path, init)

  if (!response.ok) {
    const errorBody = isJson(response) ? ((await response.json()) as ApiErrorBody) : undefined

    throw new ApiError(
      response.status,
      errorBody?.message ?? fallbackMessage ?? `API request failed: ${response.status}`,
      errorBody?.errors ?? [],
    )
  }

  return response
}

function isJson(response: Response): boolean {
  return (response.headers.get('content-type') ?? '').includes(jsonContentType)
}

export function clearCsrfToken(): void {
  csrfToken = null
  csrfTokenPromise = null
}

async function getCsrfToken(): Promise<CsrfToken> {
  if (csrfToken) {
    return csrfToken
  }

  csrfTokenPromise ??= fetchCsrfToken().finally(() => {
    csrfTokenPromise = null
  })
  csrfToken = await csrfTokenPromise
  return csrfToken
}

async function fetchCsrfToken(): Promise<CsrfToken> {
  const response = await fetch(csrfPath, {
    credentials: 'same-origin',
  })
  const contentType = response.headers.get('content-type') ?? ''

  if (!response.ok || !contentType.includes(jsonContentType)) {
    throw new ApiError(response.status, `CSRF token request failed: ${response.status}`)
  }

  return (await response.json()) as CsrfToken
}

function isUnsafeMethod(method: string): boolean {
  return !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
}
