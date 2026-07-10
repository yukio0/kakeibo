<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  disableMfa,
  enableMfa,
  getMfaStatus,
  setupMfa,
  type MfaSetup,
  type MfaStatus,
} from '@/api/kakeibo'
import { loadCurrentUser } from '@/auth'

const status = ref<MfaStatus | null>(null)
const setup = ref<MfaSetup | null>(null)
const code = ref('')
const codeError = ref<string | null>(null)
const message = ref<string | null>(null)
const errorMessage = ref<string | null>(null)
const loading = ref(true)
const processing = ref(false)

const qrCodeDataUrl = computed(() => {
  if (!setup.value) {
    return ''
  }

  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(setup.value.qrCodeSvg)}`
})

onMounted(async () => {
  await loadStatus()
})

async function loadStatus(): Promise<void> {
  loading.value = true
  errorMessage.value = null

  try {
    status.value = await getMfaStatus()
  } catch (error) {
    errorMessage.value = toMessage(error, '2段階認証の状態取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function startSetup(): Promise<void> {
  processing.value = true
  code.value = ''
  codeError.value = null
  message.value = null
  errorMessage.value = null

  try {
    setup.value = await setupMfa()
  } catch (error) {
    errorMessage.value = toMessage(error, '2段階認証の設定開始に失敗しました')
  } finally {
    processing.value = false
  }
}

async function submitEnable(): Promise<void> {
  processing.value = true
  codeError.value = null
  message.value = null
  errorMessage.value = null

  try {
    await enableMfa({ code: code.value })
    setup.value = null
    code.value = ''
    status.value = { enabled: true }
    message.value = '2段階認証を有効にしました'
    await loadCurrentUser()
  } catch (error) {
    if (error instanceof ApiError) {
      const fieldError = error.errors.find((candidate) => candidate.field === 'code')
      if (fieldError) {
        codeError.value = fieldError.message
      } else {
        errorMessage.value = error.message
      }
    } else {
      errorMessage.value = '2段階認証の有効化に失敗しました'
    }
  } finally {
    processing.value = false
  }
}

async function submitDisable(): Promise<void> {
  processing.value = true
  message.value = null
  errorMessage.value = null

  try {
    await disableMfa()
    setup.value = null
    code.value = ''
    codeError.value = null
    status.value = { enabled: false }
    message.value = '2段階認証を無効にしました'
    await loadCurrentUser()
  } catch (error) {
    errorMessage.value = toMessage(error, '2段階認証の無効化に失敗しました')
  } finally {
    processing.value = false
  }
}

function toMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}
</script>

<template>
  <section class="mfa-settings-card">
    <div class="page-heading">
      <h1>2段階認証設定</h1>
      <p>Google AuthenticatorなどのTOTP対応アプリを登録します。</p>
    </div>

    <section class="status-card mfa-settings-form">
      <p v-if="loading">読み込み中...</p>

      <template v-else>
        <p class="mfa-status">
          現在の状態:
          <span :class="status?.enabled ? 'mfa-enabled' : 'mfa-disabled'">
            {{ status?.enabled ? '有効' : '無効' }}
          </span>
        </p>

        <div v-if="status?.enabled" class="form-actions">
          <button type="button" class="danger-button" :disabled="processing" @click="submitDisable">
            無効にする
          </button>
        </div>

        <template v-else>
          <div v-if="!setup" class="form-actions">
            <button type="button" :disabled="processing" @click="startSetup">
              2段階認証を有効化する
            </button>
          </div>

          <div v-else class="mfa-setup">
            <img class="mfa-qr-code" :src="qrCodeDataUrl" alt="2段階認証設定用QRコード">

            <label class="field">
              <span>手動入力用secret</span>
              <input :value="setup.secret" type="text" readonly>
            </label>

            <form class="mfa-code-form" @submit.prevent="submitEnable">
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
                  :disabled="processing"
                >
                <small v-if="codeError" class="field-error">{{ codeError }}</small>
              </label>

              <div class="form-actions">
                <button type="submit" :disabled="processing">有効にする</button>
              </div>
            </form>
          </div>
        </template>
      </template>

      <p v-if="errorMessage" class="message error">{{ errorMessage }}</p>
      <p v-if="message" class="message success">{{ message }}</p>
    </section>
  </section>
</template>
