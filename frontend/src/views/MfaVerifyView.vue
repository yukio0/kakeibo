<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { verifyMfa } from '@/auth'

const router = useRouter()
const route = useRoute()

const code = ref('')
const codeError = ref<string | null>(null)
const errorMessage = ref<string | null>(null)
const submitting = ref(false)
const trustDevice = ref(false)

const redirectPath = computed(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/'
})

async function submitVerify(): Promise<void> {
  submitting.value = true
  codeError.value = null
  errorMessage.value = null

  try {
    await verifyMfa(code.value, trustDevice.value)
    await router.replace(redirectPath.value)
  } catch (error) {
    if (error instanceof ApiError) {
      const fieldError = error.errors.find((candidate) => candidate.field === 'code')
      if (fieldError) {
        codeError.value = fieldError.message
      } else {
        errorMessage.value = error.message
      }
    } else {
      errorMessage.value = '2段階認証に失敗しました'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="mfa-verify-card">
    <div class="page-heading">
      <h1>2段階認証</h1>
      <p>認証アプリに表示されている6桁の確認コードを入力してください。</p>
    </div>

    <form class="status-card mfa-verify-form" @submit.prevent="submitVerify">
      <label class="field">
        <span>確認コード</span>
        <input
          v-model="code"
          type="text"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          pattern="[0-9]{6}"
          required
          :disabled="submitting"
        >
        <small v-if="codeError" class="field-error">{{ codeError }}</small>
      </label>

      <label class="checkbox-field">
        <input v-model="trustDevice" type="checkbox" :disabled="submitting">
        <span>この端末では30日間2段階認証を省略する</span>
      </label>

      <p v-if="errorMessage" class="message error">{{ errorMessage }}</p>

      <div class="form-actions">
        <button type="submit" :disabled="submitting">
          {{ submitting ? '確認中...' : '確認する' }}
        </button>
      </div>
    </form>
  </section>
</template>
