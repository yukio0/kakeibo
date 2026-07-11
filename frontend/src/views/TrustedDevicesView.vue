<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  getTrustedDevices,
  revokeAllTrustedDevices,
  revokeCurrentTrustedDevice,
  revokeTrustedDevice,
  type TrustedDevice,
} from '@/api/kakeibo'

const trustedDevices = ref<TrustedDevice[]>([])
const loading = ref(true)
const processing = ref(false)
const errorMessage = ref<string | null>(null)
const message = ref<string | null>(null)

onMounted(async () => {
  await loadTrustedDevices()
})

async function loadTrustedDevices(): Promise<void> {
  loading.value = true
  errorMessage.value = null

  try {
    trustedDevices.value = await getTrustedDevices()
  } catch (error) {
    errorMessage.value = toMessage(error, '信頼済み端末の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function revokeDevice(device: TrustedDevice): Promise<void> {
  await runAction(async () => {
    await revokeTrustedDevice(device.id)
    message.value = '信頼済み端末を解除しました'
  })
}

async function revokeCurrentDevice(): Promise<void> {
  await runAction(async () => {
    await revokeCurrentTrustedDevice()
    message.value = '現在の端末の信頼を解除しました'
  })
}

async function revokeAllDevices(): Promise<void> {
  await runAction(async () => {
    await revokeAllTrustedDevices()
    message.value = 'すべての信頼済み端末を解除しました'
  })
}

async function runAction(action: () => Promise<void>): Promise<void> {
  processing.value = true
  errorMessage.value = null
  message.value = null

  try {
    await action()
    await loadTrustedDevices()
  } catch (error) {
    errorMessage.value = toMessage(error, '信頼済み端末の更新に失敗しました')
  } finally {
    processing.value = false
  }
}

function formatDateTime(value: string | null): string {
  if (!value) {
    return '-'
  }

  return new Intl.DateTimeFormat('ja-JP', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function toMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}
</script>

<template>
  <section class="trusted-devices-section">
    <div class="page-heading">
      <h1>信頼済み端末管理</h1>
      <p>2段階認証を30日間省略できる端末を確認・解除します。</p>
    </div>

    <section class="status-card">
      <p v-if="loading">読み込み中...</p>

      <template v-else>
        <div class="trusted-device-actions">
          <button type="button" :disabled="processing" @click="revokeCurrentDevice">
            現在の端末を解除
          </button>
          <button
            type="button"
            class="danger-button"
            :disabled="processing"
            @click="revokeAllDevices"
          >
            すべて解除
          </button>
        </div>

        <p v-if="trustedDevices.length === 0" class="empty-message">信頼済み端末はありません。</p>

        <table v-else class="trusted-device-table">
          <thead>
            <tr>
              <th>端末</th>
              <th>最終使用</th>
              <th>有効期限</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="device in trustedDevices" :key="device.id">
              <td>
                <span>{{ device.deviceName }}</span>
                <span v-if="device.current" class="current-device-badge">現在の端末</span>
              </td>
              <td>{{ formatDateTime(device.lastUsedAt) }}</td>
              <td>{{ formatDateTime(device.expiresAt) }}</td>
              <td>
                <button
                  type="button"
                  class="danger-button"
                  :disabled="processing"
                  @click="revokeDevice(device)"
                >
                  解除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>

      <p v-if="errorMessage" class="message error">{{ errorMessage }}</p>
      <p v-if="message" class="message success">{{ message }}</p>
    </section>
  </section>
</template>
