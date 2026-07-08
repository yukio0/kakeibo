package jp.yukio0.kakeibo

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class HelloControllerTests {

  private val mockMvc = MockMvcBuilders.standaloneSetup(HelloController()).build()

  @Test
  fun helloReturnsHelloWorld() {
    mockMvc
      .perform(get("/hello"))
      .andExpect(status().isOk)
      .andExpect(content().string("Hello World!"))
  }
}
