<script setup lang="ts">
import { useRouter } from 'vue-router'
import { authState, logout } from '@/auth'

const router = useRouter()

async function handleLogout(): Promise<void> {
  await logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="app-title" to="/">家計簿</RouterLink>
      <div v-if="authState.user" class="app-header-actions">
        <nav class="app-nav" aria-label="メインナビゲーション">
          <RouterLink to="/">家計簿入力</RouterLink>
          <RouterLink to="/categories">カテゴリ管理</RouterLink>
          <RouterLink to="/payment-methods">支払い方法管理</RouterLink>
          <RouterLink to="/transfers">振替管理</RouterLink>
          <RouterLink v-if="authState.security?.authenticationEnabled" to="/password">パスワード変更</RouterLink>
          <RouterLink v-if="authState.security?.twoFactorEnabled" to="/mfa/settings">2FA設定</RouterLink>
          <RouterLink v-if="authState.security?.twoFactorEnabled" to="/trusted-devices">信頼済み端末</RouterLink>
        </nav>
        <div v-if="authState.security?.authenticationEnabled" class="app-user-actions">
          <span class="app-user-name">{{ authState.user.username }}</span>
          <button class="logout-button" type="button" @click="handleLogout">ログアウト</button>
        </div>
      </div>
    </header>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
