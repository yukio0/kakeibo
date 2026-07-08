package jp.yukio0.kakeibo

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class DatabaseConnectionTests {

  @Autowired private lateinit var jdbcTemplate: JdbcTemplate

  @Test
  fun databaseConnectionIsAvailable() {
    assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Int::class.java))
  }
}
