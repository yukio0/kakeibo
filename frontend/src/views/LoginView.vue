<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { login } from '@/auth'

const router = useRouter()
const route = useRoute()

const form = reactive({
  username: '',
  password: '',
})
const submitting = ref(false)
const errorMessage = ref<string | null>(null)

const redirectPath = computed(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/'
})

async function submitLogin(): Promise<void> {
  submitting.value = true
  errorMessage.value = null

  try {
    const response = await login(form.username, form.password)
    if (response.mfaRequired) {
      await router.replace({
        name: 'mfa-verify',
        query: {
          redirect: redirectPath.value,
        },
      })
      return
    }

    await router.replace(redirectPath.value)
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message
    } else {
      errorMessage.value = 'ログインに失敗しました'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="login-card">
    <div class="page-heading">
      <h1>ログイン</h1>
      <p>家計簿を利用するにはログインしてください。</p>
    </div>

    <form class="status-card login-form" @submit.prevent="submitLogin">
      <label class="field">
        <span>ユーザー名</span>
        <input
          v-model="form.username"
          type="text"
          autocomplete="username"
          required
          :disabled="submitting"
        >
      </label>

      <label class="field">
        <span>パスワード</span>
        <input
          v-model="form.password"
          type="password"
          autocomplete="current-password"
          required
          :disabled="submitting"
        >
      </label>

      <p v-if="errorMessage" class="message error">{{ errorMessage }}</p>

      <div class="form-actions">
        <button type="submit" :disabled="submitting">
          {{ submitting ? 'ログイン中...' : 'ログイン' }}
        </button>
      </div>
    </form>
  </section>
</template>
