package jp.yukio0.kakeibo.paymentmethod

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import jp.yukio0.kakeibo.master.MasterRequest

data class PaymentMethodRequest(
  @field:NotBlank(message = "支払い方法名を入力してください")
  @field:Size(max = 100, message = "支払い方法名は100文字以内で入力してください")
  override val name: String?,
  @field:NotNull(message = "表示順を入力してください")
  @field:Min(value = 0, message = "表示順は0以上で入力してください")
  override val displayOrder: Int?,
) : MasterRequest

data class PaymentMethodResponse(
  val id: Long,
  val name: String,
  val displayOrder: Int,
)
