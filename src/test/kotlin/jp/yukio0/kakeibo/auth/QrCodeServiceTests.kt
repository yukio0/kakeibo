package jp.yukio0.kakeibo.auth

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class QrCodeServiceTests {

  private val qrCodeService = QrCodeService()

  @Test
  fun svgDecodesBackToOriginalContent() {
    val content =
      "otpauth://totp/Kakeibo%3Ayukio?secret=JBSWY3DPEHPK3PXP&issuer=Kakeibo" +
        "&algorithm=SHA1&digits=6&period=30"

    val decoded = decode(qrCodeService.toSvg(content))

    assertEquals(content, decoded)
  }

  /** モジュール単位で描くことでSVGが小さく収まることを確認する。ピクセル単位だと数百KBになる。 */
  @Test
  fun svgStaysSmallEnoughToEmbedInJsonResponse() {
    val svg = qrCodeService.toSvg("otpauth://totp/Kakeibo%3Ayukio?secret=JBSWY3DPEHPK3PXP")

    assertTrue(svg.length < MAX_SVG_LENGTH, "SVGが大きすぎます: ${svg.length}")
  }

  @Test
  fun svgKeepsQuietZoneAroundTheSymbol() {
    val matrix =
      toBitMatrix(qrCodeService.toSvg("otpauth://totp/Kakeibo%3Ayukio?secret=JBSWY3DPEH"))

    // 外周の静粛領域が塗られていないこと(4モジュール分)
    (0 until matrix.width).forEach { x ->
      (0 until QUIET_ZONE_MODULES).forEach { offset ->
        assertTrue(!matrix[x, offset], "上端の静粛領域が塗られています")
        assertTrue(!matrix[x, matrix.height - 1 - offset], "下端の静粛領域が塗られています")
      }
    }
    (0 until matrix.height).forEach { y ->
      (0 until QUIET_ZONE_MODULES).forEach { offset ->
        assertTrue(!matrix[offset, y], "左端の静粛領域が塗られています")
        assertTrue(!matrix[matrix.width - 1 - offset, y], "右端の静粛領域が塗られています")
      }
    }
  }

  private fun decode(svg: String): String {
    val bitmap = BinaryBitmap(GlobalHistogramBinarizer(BitMatrixLuminanceSource(toBitMatrix(svg))))
    // 画像は歪みのないQRそのものなので PURE_BARCODE を指定して確実に読ませる。
    return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
  }

  /** SVGのviewBoxとパスから、描画された黒モジュールを復元する。 */
  private fun toBitMatrix(svg: String): BitMatrix {
    val size = VIEW_BOX_PATTERN.find(svg)?.groupValues?.get(1)?.toInt() ?: error("viewBoxが見つかりません")
    val matrix = BitMatrix(size, size)
    MODULE_PATTERN.findAll(svg).forEach { module ->
      matrix.set(module.groupValues[1].toInt(), module.groupValues[2].toInt())
    }
    return matrix
  }

  private class BitMatrixLuminanceSource(private val matrix: BitMatrix) :
    LuminanceSource(matrix.width, matrix.height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
      val result = if (row == null || row.size < width) ByteArray(width) else row
      (0 until width).forEach { x -> result[x] = luminanceAt(x, y) }
      return result
    }

    override fun getMatrix(): ByteArray {
      val result = ByteArray(width * height)
      (0 until height).forEach { y ->
        (0 until width).forEach { x -> result[y * width + x] = luminanceAt(x, y) }
      }
      return result
    }

    private fun luminanceAt(x: Int, y: Int): Byte = if (matrix[x, y]) BLACK else WHITE
  }

  private companion object {
    private const val QUIET_ZONE_MODULES = 4
    private const val MAX_SVG_LENGTH = 20_000
    private const val BLACK: Byte = 0
    private const val WHITE: Byte = -1
    private val VIEW_BOX_PATTERN = Regex("""viewBox="0 0 (\d+) \d+"""")
    private val MODULE_PATTERN = Regex("""M(\d+),(\d+) h1 v1 h-1z""")
  }
}
