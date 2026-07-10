import { createHmac } from 'node:crypto'

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

export function generateTotp(secret: string, timestamp = Date.now()): string {
  const counter = Math.floor(timestamp / 1000 / 30)
  const counterBuffer = Buffer.alloc(8)
  counterBuffer.writeBigUInt64BE(BigInt(counter))

  const digest = createHmac('sha1', decodeBase32(secret)).update(counterBuffer).digest()
  const offset = digest[digest.length - 1] & 0x0f
  const binaryCode =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff)

  return String(binaryCode % 1_000_000).padStart(6, '0')
}

function decodeBase32(value: string): Buffer {
  let bits = 0
  let buffer = 0
  const bytes: number[] = []

  for (const character of value.replaceAll('=', '').toUpperCase()) {
    const index = BASE32_ALPHABET.indexOf(character)
    if (index === -1) {
      throw new Error('TOTPシークレットがBase32形式ではありません')
    }

    buffer = (buffer << 5) | index
    bits += 5

    if (bits >= 8) {
      bytes.push((buffer >>> (bits - 8)) & 0xff)
      bits -= 8
    }
  }

  return Buffer.from(bytes)
}
