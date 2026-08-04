package jp.yukio0.kakeibo.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.springframework.stereotype.Service

@Service
class QrCodeService {

  /**
   * QRコードをSVGとして返す。
   *
   * 幅・高さに0を渡してモジュール1つ=1単位の最小行列を作り、拡大はviewBoxとCSSに任せる。 ピクセル単位で描くとパスがモジュール数の数十倍に膨らみ、SVGが数百KBになる。
   */
  fun toSvg(content: String): String {
    val matrix =
      QRCodeWriter()
        .encode(
          content,
          BarcodeFormat.QR_CODE,
          0,
          0,
          mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
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
    // 静粛領域(quiet zone)はモジュール数で指定する。QRの仕様が推奨する4モジュールを確保する。
    // 以前はピクセル単位の2で、モジュール換算では0.5未満しかなく読み取りに不利だった。
    private const val QUIET_ZONE_MODULES = 4
  }
}
