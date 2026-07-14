<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { authState, logout } from '@/auth'

const router = useRouter()
const menuOpen = ref(false)
const navClose = ref<HTMLButtonElement | null>(null)

function closeMenu(): void {
  menuOpen.value = false
}

async function handleLogout(): Promise<void> {
  closeMenu()
  await logout()
  await router.replace({ name: 'login' })
}

// 画面遷移したらドロワーを閉じる
const stopAfterEach = router.afterEach(() => closeMenu())

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    closeMenu()
  }
}

// 開いたらドロワー先頭の閉じるボタンへフォーカスを移す
watch(menuOpen, (open) => {
  if (open) {
    void nextTick(() => navClose.value?.focus())
  }
})

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  stopAfterEach()
})
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="app-title" to="/">家計簿</RouterLink>

      <template v-if="authState.user">
        <button
          type="button"
          class="nav-toggle"
          :aria-expanded="menuOpen"
          aria-controls="app-nav-drawer"
          aria-label="メニュー"
          @click="menuOpen = !menuOpen"
        >
          <span aria-hidden="true">☰</span>
        </button>

        <div id="app-nav-drawer" class="app-header-actions" :class="{ open: menuOpen }">
          <button
            ref="navClose"
            type="button"
            class="nav-close"
            aria-label="メニューを閉じる"
            @click="closeMenu"
          >
            <span aria-hidden="true">×</span>
          </button>

          <nav class="app-nav" aria-label="メインナビゲーション" @click="closeMenu">
            <RouterLink to="/">家計簿入力</RouterLink>
            <RouterLink to="/summary">集計</RouterLink>
            <RouterLink to="/csv-export">CSV入出力</RouterLink>
            <RouterLink to="/categories">カテゴリ管理</RouterLink>
            <RouterLink to="/payment-methods">支払い方法管理</RouterLink>
            <RouterLink to="/transfers">振替管理</RouterLink>
            <RouterLink v-if="authState.security?.authenticationEnabled" to="/password"
              >パスワード変更</RouterLink
            >
            <RouterLink v-if="authState.security?.twoFactorEnabled" to="/mfa/settings"
              >2FA設定</RouterLink
            >
            <RouterLink v-if="authState.security?.twoFactorEnabled" to="/trusted-devices"
              >信頼済み端末</RouterLink
            >
          </nav>

          <div v-if="authState.security?.authenticationEnabled" class="app-user-actions">
            <span class="app-user-name">{{ authState.user.username }}</span>
            <button class="logout-button" type="button" @click="handleLogout">ログアウト</button>
          </div>
        </div>
      </template>
    </header>

    <div v-if="menuOpen" class="nav-overlay" @click="closeMenu"></div>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
