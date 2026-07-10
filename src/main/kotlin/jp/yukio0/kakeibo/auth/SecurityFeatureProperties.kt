package jp.yukio0.kakeibo.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SecurityFeatureProperties(
  @Value("\${kakeibo.security.authentication-enabled:true}") val authenticationEnabled: Boolean,
  @Value("\${kakeibo.security.two-factor-enabled:true}")
  private val configuredTwoFactorEnabled: Boolean,
) {

  val twoFactorEnabled: Boolean
    get() = authenticationEnabled && configuredTwoFactorEnabled
}
