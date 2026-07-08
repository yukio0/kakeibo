package jp.yukio0.kakeibo.api

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class GlobalExceptionHandlerTests {

  private val mockMvc =
    MockMvcBuilders.standaloneSetup(ErrorTestController())
      .setControllerAdvice(GlobalExceptionHandler())
      .build()

  @Test
  fun resourceNotFoundUsesCommonErrorResponse() {
    mockMvc
      .perform(get("/test/not-found"))
      .andExpect(status().isNotFound)
      .andExpect(content().contentType(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.message").value("対象データがありません"))
      .andExpect(jsonPath("$.errors").isEmpty)
  }

  @RestController
  private class ErrorTestController {

    @GetMapping("/test/not-found")
    fun notFound(): Nothing = throw ResourceNotFoundException("対象データがありません")
  }
}
