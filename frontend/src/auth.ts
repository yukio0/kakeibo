import { reactive } from 'vue'
import { ApiError } from '@/api/http'
import {
  getCurrentUser,
  getSecuritySettings,
  login as loginRequest,
  logout as logoutRequest,
  verifyMfa as verifyMfaRequest,
  type AuthUser,
  type LoginResponse,
  type SecuritySettings,
} from '@/api/kakeibo'

export const authState = reactive<{
  user: AuthUser | null
  security: SecuritySettings | null
  loaded: boolean
}>({
  user: null,
  security: null,
  loaded: false,
})

export async function loadCurrentUser(): Promise<AuthUser | null> {
  try {
    authState.security = await getSecuritySettings()
    return await refreshCurrentUser()
  } finally {
    authState.loaded = true
  }
}

export async function refreshCurrentUser(): Promise<AuthUser | null> {
  try {
    authState.user = await getCurrentUser()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      authState.user = null
    } else {
      throw error
    }
  }
  return authState.user
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  const response = await loginRequest({
    username,
    password,
  })
  authState.user = response.user
  authState.loaded = true
  return response
}

export async function verifyMfa(code: string, trustDevice: boolean): Promise<AuthUser> {
  const user = await verifyMfaRequest({ code, trustDevice })
  authState.user = user
  authState.loaded = true
  return user
}

export async function logout(): Promise<void> {
  if (authState.security?.authenticationEnabled !== false) {
    await logoutRequest()
    authState.user = null
  }
  authState.loaded = true
}
