import { reactive } from 'vue'
import { ApiError } from '@/api/http'
import {
  getCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  type AuthUser,
} from '@/api/kakeibo'

export const authState = reactive<{
  user: AuthUser | null
  loaded: boolean
}>({
  user: null,
  loaded: false,
})

export async function loadCurrentUser(): Promise<AuthUser | null> {
  try {
    authState.user = await getCurrentUser()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      authState.user = null
    } else {
      throw error
    }
  } finally {
    authState.loaded = true
  }

  return authState.user
}

export async function login(username: string, password: string): Promise<AuthUser> {
  const user = await loginRequest({
    username,
    password,
  })
  authState.user = user
  authState.loaded = true
  return user
}

export async function logout(): Promise<void> {
  await logoutRequest()
  authState.user = null
  authState.loaded = true
}
