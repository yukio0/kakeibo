<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import {
  disableMfa,
  enableMfa,
  getMfaRecoveryCodeStatus,
  getMfaStatus,
  regenerateMfaRecoveryCodes,
  setupMfa,
  type MfaRecoveryCodeStatus,
  type MfaSetup,
  type MfaStatus,
} from '@/api/kakeibo'
import { loadCurrentUser } from '@/auth'

const route = useRoute()
const router = useRouter()
const status = ref<MfaStatus | null>(null)
const setup = ref<MfaSetup | null>(null)
const code = ref('')
const codeError = ref<string | null>(null)
const message = ref<string | null>(null)
const errorMessage = ref<string | null>(null)
const loading = ref(true)
const processing = ref(false)
const recoveryCodes = ref<string[] | null>(null)
const recoveryCodeStatus = ref<MfaRecoveryCodeStatus | null>(null)

// リカバリーコードでログインした直後は、残数とともに再設定を促す
const recoveryLoginRemaining = computed(() => {
  const value = route.query['recovery-used']
  const raw = Array.isArray(value) ? value[0] : value
  const remaining = Number(raw)
  return typeof raw === 'string' && raw !== '' && Number.isInteger(remaining) ? remaining : null
})

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
    recoveryCodeStatus.value = status.value.enabled ? await getMfaRecoveryCodeStatus() : null
  } catch (error) {
    errorMessage.value = toMessage(error, '2段階認証の状態取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function submitRegenerateRecoveryCodes(): Promise<void> {
  if (!window.confirm('これまでのリカバリーコードは使えなくなります。再発行しますか？')) {
    return
  }

  processing.value = true
  message.value = null
  errorMessage.value = null

  try {
    recoveryCodes.value = (await regenerateMfaRecoveryCodes()).recoveryCodes
    recoveryCodeStatus.value = await getMfaRecoveryCodeStatus()
    message.value = 'リカバリーコードを再発行しました'
    // 再発行で残数が戻るので、リカバリーコードログイン直後の警告は取り下げる
    if (recoveryLoginRemaining.value !== null) {
      await router.replace({ name: 'mfa-settings' })
    }
  } catch (error) {
    errorMessage.value = toMessage(error, 'リカバリーコードの再発行に失敗しました')
  } finally {
    processing.value = false
  }
}

async function copyRecoveryCodes(): Promise<void> {
  if (!recoveryCodes.value) {
    return
  }

  try {
    await navigator.clipboard.writeText(recoveryCodes.value.join('\n'))
    message.value = 'リカバリーコードをコピーしました'
  } catch {
    errorMessage.value = 'コピーできませんでした。手動で控えてください'
  }
}

function downloadRecoveryCodes(): void {
  if (!recoveryCodes.value) {
    return
  }

  const blob = new Blob([`${recoveryCodes.value.join('\n')}\n`], {
    type: 'text/plain;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'kakeibo-recovery-codes.txt'
  link.click()
  URL.revokeObjectURL(url)
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
    recoveryCodes.value = (await enableMfa({ code: code.value })).recoveryCodes
    setup.value = null
    code.value = ''
    status.value = { enabled: true }
    recoveryCodeStatus.value = await getMfaRecoveryCodeStatus()
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
    recoveryCodes.value = null
    recoveryCodeStatus.value = null
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
      <p v-if="recoveryLoginRemaining !== null" class="message warning">
        リカバリーコードでログインしました。残り{{
          recoveryLoginRemaining
        }}個です。認証アプリを使えない場合は、2段階認証をいったん無効にしてから新しい端末で登録し直してください。
      </p>

      <p v-if="loading">読み込み中...</p>

      <template v-else>
        <p class="mfa-status">
          現在の状態:
          <span :class="status?.enabled ? 'mfa-enabled' : 'mfa-disabled'">
            {{ status?.enabled ? '有効' : '無効' }}
          </span>
        </p>

        <template v-if="status?.enabled">
          <p v-if="recoveryCodeStatus" class="mfa-status">
            リカバリーコード:
            <span :class="recoveryCodeStatus.remaining > 0 ? 'mfa-enabled' : 'mfa-disabled'">
              残り{{ recoveryCodeStatus.remaining }}個 / {{ recoveryCodeStatus.total }}個
            </span>
          </p>

          <div class="form-actions">
            <button type="button" :disabled="processing" @click="submitRegenerateRecoveryCodes">
              リカバリーコードを再発行する
            </button>
          </div>

          <div class="form-actions">
            <button
              type="button"
              class="danger-button"
              :disabled="processing"
              @click="submitDisable"
            >
              無効にする
            </button>
          </div>
        </template>

        <template v-else>
          <div v-if="!setup" class="form-actions">
            <button type="button" :disabled="processing" @click="startSetup">
              2段階認証を有効化する
            </button>
          </div>

          <div v-else class="mfa-setup">
            <img class="mfa-qr-code" :src="qrCodeDataUrl" alt="2段階認証設定用QRコード" />

            <label class="field">
              <span>手動入力用secret</span>
              <input :value="setup.secret" type="text" readonly />
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
                />
                <small v-if="codeError" class="field-error">{{ codeError }}</small>
              </label>

              <div class="form-actions">
                <button type="submit" :disabled="processing">有効にする</button>
              </div>
            </form>
          </div>
        </template>
      </template>

      <div v-if="recoveryCodes" class="mfa-recovery-codes">
        <p class="message warning">
          リカバリーコードは今だけ表示されます。認証アプリを使えなくなったときのログインに使うので、印刷するか安全な場所に保管してください。1つのコードは1回だけ使えます。
        </p>

        <ul class="mfa-recovery-code-list">
          <li v-for="recoveryCode in recoveryCodes" :key="recoveryCode">{{ recoveryCode }}</li>
        </ul>

        <div class="form-actions">
          <button type="button" class="secondary-button" @click="copyRecoveryCodes">
            コピーする
          </button>
          <button type="button" class="secondary-button" @click="downloadRecoveryCodes">
            ファイルに保存する
          </button>
          <button type="button" @click="recoveryCodes = null">控えたので閉じる</button>
        </div>
      </div>

      <p v-if="errorMessage" class="message error">{{ errorMessage }}</p>
      <p v-if="message" class="message success">{{ message }}</p>
    </section>
  </section>
</template>
