package jp.yukio0.kakeibo.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.springframework.stereotype.Service

@Service
class QrCodeService {

  fun toSvg(content: String): String {
    val matrix =
      QRCodeWriter()
        .encode(
          content,
          BarcodeFormat.QR_CODE,
          QR_CODE_SIZE,
          QR_CODE_SIZE,
          mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QR_CODE_MARGIN,
          ),
        )

    val path = StringBuilder()
    for (y in 0 until matrix.height) {
      for (x in 0 until matrix.width) {
        if (matrix[x, y]) {
          path.append("M$x,$y h1 v1 h-1z ")
        }
      }
    }

    return """
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" role="img" aria-label="2FA QR code" shape-rendering="crispEdges">
        <rect width="100%" height="100%" fill="#ffffff"/>
        <path d="${path.toString().trim()}" fill="#111827"/>
      </svg>
      """
      .trimIndent()
  }

  private companion object {
    private const val QR_CODE_SIZE = 240
    private const val QR_CODE_MARGIN = 2
  }
}
