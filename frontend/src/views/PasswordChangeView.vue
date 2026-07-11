<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ApiError } from '@/api/http'
import { changePassword } from '@/api/kakeibo'

type PasswordForm = {
  currentPassword: string
  newPassword: string
  newPasswordConfirm: string
}

type PasswordField = keyof PasswordForm
type FieldErrors = Partial<Record<PasswordField, string>>

const form = reactive<PasswordForm>({
  currentPassword: '',
  newPassword: '',
  newPasswordConfirm: '',
})
const fieldErrors = reactive<FieldErrors>({})
const errorMessage = ref<string | null>(null)
const successMessage = ref<string | null>(null)
const submitting = ref(false)

async function submitPasswordChange(): Promise<void> {
  submitting.value = true
  errorMessage.value = null
  successMessage.value = null
  clearFieldErrors()

  try {
    await changePassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
      newPasswordConfirm: form.newPasswordConfirm,
    })
    form.currentPassword = ''
    form.newPassword = ''
    form.newPasswordConfirm = ''
    successMessage.value = 'パスワードを変更しました'
  } catch (error) {
    if (error instanceof ApiError) {
      applyApiError(error)
    } else {
      errorMessage.value = 'パスワード変更に失敗しました'
    }
  } finally {
    submitting.value = false
  }
}

function applyApiError(error: ApiError): void {
  error.errors.forEach((fieldError) => {
    if (isPasswordField(fieldError.field)) {
      fieldErrors[fieldError.field] = fieldError.message
    }
  })

  if (Object.keys(fieldErrors).length === 0) {
    errorMessage.value = error.message
  }
}

function isPasswordField(field: string): field is PasswordField {
  return field === 'currentPassword' || field === 'newPassword' || field === 'newPasswordConfirm'
}

function clearFieldErrors(): void {
  Object.keys(fieldErrors).forEach((field) => {
    delete fieldErrors[field as PasswordField]
  })
}
</script>

<template>
  <section class="password-change-card">
    <div class="page-heading">
      <h1>パスワード変更</h1>
      <p>ログインに使用するパスワードを変更します。</p>
    </div>

    <form class="status-card password-change-form" @submit.prevent="submitPasswordChange">
      <label class="field">
        <span>現在のパスワード</span>
        <input
          v-model="form.currentPassword"
          type="password"
          autocomplete="current-password"
          required
          :disabled="submitting"
        />
        <small v-if="fieldErrors.currentPassword" class="field-error">
          {{ fieldErrors.currentPassword }}
        </small>
      </label>

      <label class="field">
        <span>新しいパスワード</span>
        <input
          v-model="form.newPassword"
          type="password"
          autocomplete="new-password"
          required
          :disabled="submitting"
        />
        <small v-if="fieldErrors.newPassword" class="field-error">
          {{ fieldErrors.newPassword }}
        </small>
      </label>

      <label class="field">
        <span>新しいパスワード（確認）</span>
        <input
          v-model="form.newPasswordConfirm"
          type="password"
          autocomplete="new-password"
          required
          :disabled="submitting"
        />
        <small v-if="fieldErrors.newPasswordConfirm" class="field-error">
          {{ fieldErrors.newPasswordConfirm }}
        </small>
      </label>

      <p v-if="errorMessage" class="message error">{{ errorMessage }}</p>
      <p v-if="successMessage" class="message success">{{ successMessage }}</p>

      <div class="form-actions">
        <button type="submit" :disabled="submitting">
          {{ submitting ? '変更中...' : '変更する' }}
        </button>
      </div>
    </form>
  </section>
</template>
