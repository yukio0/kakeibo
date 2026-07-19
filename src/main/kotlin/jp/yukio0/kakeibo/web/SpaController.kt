package jp.yukio0.kakeibo.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SpaController {

  @GetMapping(
    value =
      [
        "/",
        "/login",
        "/mfa/verify",
        "/recurring-templates",
        "/categories",
        "/csv-export",
        "/summary",
        "/payment-methods",
        "/password",
        "/mfa/settings",
        "/trusted-devices",
        "/transfers",
      ]
  )
  fun forwardToIndex(): String = "forward:/index.html"
}
