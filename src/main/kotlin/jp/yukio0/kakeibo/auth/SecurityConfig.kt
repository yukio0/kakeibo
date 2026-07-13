package jp.yukio0.kakeibo.auth

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
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
import org.springframework.security.web.header.writers.StaticHeadersWriter

@Configuration
@EnableWebSecurity
class SecurityConfig(
  private val securityFeatureProperties: SecurityFeatureProperties,
  private val environment: Environment,
) {

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
      .headers { headers ->
        headers.addHeaderWriter(StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow"))
      }
      .securityContext { securityContext ->
        securityContext.securityContextRepository(securityContextRepository)
      }
      .authorizeHttpRequests { authorize ->
        if (!securityFeatureProperties.authenticationEnabled) {
          authorize.anyRequest().permitAll()
          return@authorizeHttpRequests
        }

        authorize
          .requestMatchers(HttpMethod.GET, "/hello", "/api/csrf", "/api/security-settings")
          .permitAll()
          .requestMatchers(HttpMethod.POST, "/api/login")
          .permitAll()
          .requestMatchers(HttpMethod.POST, "/api/mfa/verify")
          .permitAll()
          .requestMatchers(
            HttpMethod.GET,
            "/",
            "/index.html",
            "/login",
            "/mfa/verify",
            "/categories",
            "/csv-export",
            "/summary",
            "/mfa/settings",
            "/payment-methods",
            "/password",
            "/transfers",
            "/trusted-devices",
            "/assets/**",
            // PWA/アイコン類は未ログイン(ログイン画面表示時やインストール時)にも取得できる必要がある
            "/site.webmanifest",
            "/icon-192.png",
            "/icon-512.png",
            "/apple-touch-icon.png",
            "/favicon.ico",
            "/favicon.svg",
            "/favicon-16x16.png",
            "/favicon-32x32.png",
            "/favicon-48x48.png",
            "/vite.svg",
          )
          .permitAll()

        // E2eDataController と同じ条件でだけ開ける。プロファイルの有無と許可がずれると、
        // 全データを消して既知の認証情報を作るエンドポイントを未認証で叩けてしまう。
        if (environment.acceptsProfiles(Profiles.of(E2E_PROFILE))) {
          authorize.requestMatchers(HttpMethod.POST, E2E_RESET_PATH).permitAll()
        }

        authorize.anyRequest().authenticated()
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

  private companion object {
    const val E2E_PROFILE = "e2e"
    const val E2E_RESET_PATH = "/api/e2e/reset"
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
