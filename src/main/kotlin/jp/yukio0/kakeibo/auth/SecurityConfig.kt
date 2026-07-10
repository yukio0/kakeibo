package jp.yukio0.kakeibo.auth

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository

@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

  @Bean
  fun authenticationManager(
    authenticationConfiguration: AuthenticationConfiguration
  ): AuthenticationManager = authenticationConfiguration.authenticationManager

  @Bean
  fun securityContextRepository(): SecurityContextRepository =
    HttpSessionSecurityContextRepository()

  @Bean
  fun securityFilterChain(
    http: HttpSecurity,
    securityContextRepository: SecurityContextRepository,
  ): SecurityFilterChain =
    http
      .csrf { csrf ->
        csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
      }
      .securityContext { securityContext ->
        securityContext.securityContextRepository(securityContextRepository)
      }
      .authorizeHttpRequests { authorize ->
        authorize
          .requestMatchers(HttpMethod.GET, "/hello", "/api/csrf")
          .permitAll()
          .requestMatchers(HttpMethod.POST, "/api/login")
          .permitAll()
          .requestMatchers(
            HttpMethod.GET,
            "/",
            "/index.html",
            "/login",
            "/categories",
            "/payment-methods",
            "/password",
            "/transfers",
            "/assets/**",
            "/favicon.ico",
            "/vite.svg",
          )
          .permitAll()
          .anyRequest()
          .authenticated()
      }
      .formLogin { formLogin -> formLogin.disable() }
      .httpBasic { httpBasic -> httpBasic.disable() }
      .logout { logout -> logout.disable() }
      .sessionManagement { session ->
        session.sessionFixation { sessionFixation -> sessionFixation.changeSessionId() }
      }
      .exceptionHandling { exceptions ->
        exceptions
          .authenticationEntryPoint { _, response, _ ->
            writeError(
              response = response,
              status = HttpStatus.UNAUTHORIZED,
              message = "認証が必要です",
            )
          }
          .accessDeniedHandler { _, response, _ ->
            writeError(
              response = response,
              status = HttpStatus.FORBIDDEN,
              message = "アクセスが拒否されました",
            )
          }
      }
      .build()

  private fun writeError(
    response: HttpServletResponse,
    status: HttpStatus,
    message: String,
  ) {
    response.status = status.value()
    response.characterEncoding = "UTF-8"
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.writer.write("""{"message":"${escapeJson(message)}"}""")
  }

  private fun escapeJson(value: String): String = buildString {
    value.forEach { character ->
      when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> {
          if (character.code < 0x20) {
            append("\\u%04x".format(character.code))
          } else {
            append(character)
          }
        }
      }
    }
  }
}
