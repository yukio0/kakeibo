plugins {
  kotlin("jvm") version "2.3.21"
  kotlin("plugin.jpa") version "2.3.21"
  kotlin("plugin.spring") version "2.3.21"
  id("org.springframework.boot") version "4.1.0"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless") version "8.8.0"
}

val e2eRuntimeOnly = configurations.create("e2eRuntimeOnly")
val e2eDirectory = layout.projectDirectory.dir("e2e")
val npmExecutable =
  if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "npm.cmd" else "npm"

group = "jp.yukio0"

version = "0.0.1-SNAPSHOT"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

repositories {
  mavenCentral()
}

dependencies {
  e2eRuntimeOnly("com.h2database:h2")
  implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")
  implementation("com.google.zxing:core:3.5.3")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  runtimeOnly("org.flywaydb:flyway-database-postgresql")
  runtimeOnly("org.postgresql:postgresql")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
  testRuntimeOnly("com.h2database:h2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
  classpath = classpath + e2eRuntimeOnly
}

val e2eInstall =
  tasks.register<Exec>("e2eInstall") {
    group = "verification"
    description = "E2Eテスト用のnpm依存関係をインストールします。"
    workingDir(e2eDirectory)
    commandLine(npmExecutable, "ci")
    inputs.files(e2eDirectory.file("package.json"), e2eDirectory.file("package-lock.json"))
    outputs.dir(e2eDirectory.dir("node_modules"))
  }

val e2eInstallBrowsers =
  tasks.register<Exec>("e2eInstallBrowsers") {
    group = "verification"
    description = "E2Eテスト用のChromiumを準備します。"
    dependsOn(e2eInstall)
    workingDir(e2eDirectory)
    commandLine(npmExecutable, "run", "install:browsers")
    inputs.file(e2eDirectory.file("package-lock.json"))
    outputs.upToDateWhen { false }
  }

tasks.register<Exec>("e2eTest") {
  group = "verification"
  description = "PlaywrightでE2Eテストを実行します。"
  dependsOn(e2eInstallBrowsers)
  workingDir(e2eDirectory)
  commandLine(npmExecutable, "run", "test")
}

// shellcheck / shfmt は公式Dockerイメージで実行する。
// タグでバージョンを固定して再現性を確保する。実行には Docker が起動している必要がある。
val shellcheckImage = "koalaman/shellcheck:v0.10.0"
val shfmtImage = "mvdan/shfmt:v3.10.0"
// インデント2スペース(既存スクリプト・プロジェクトに合わせる) / case を字下げ / 二項演算子を行頭に置く
val shfmtArgs = arrayOf("-i", "2", "-ci", "-bn")
val shellScriptsDir = "scripts"
val shellScriptFiles =
  fileTree(shellScriptsDir) { include("**/*.sh") }
    .files
    .map { it.relativeTo(projectDir).invariantSeparatorsPath }
    .sorted()

// カレントを /work にマウントして、リポジトリ相対パスのまま渡す。
fun dockerRun(vararg command: String): List<String> =
  listOf("docker", "run", "--rm", "-v", "$projectDir:/work", "-w", "/work") + command

tasks.register<Exec>("shellcheck") {
  group = "verification"
  description = "shellcheck でシェルスクリプトを静的解析します。"
  commandLine(dockerRun(shellcheckImage, *shellScriptFiles.toTypedArray()))
  inputs.files(shellScriptFiles.map { file(it) })
  outputs.upToDateWhen { true }
}

tasks.register<Exec>("shfmtCheck") {
  group = "verification"
  description = "shfmt でシェルスクリプトの整形崩れを検出します(差分があれば失敗)。"
  commandLine(dockerRun(shfmtImage, "-d", *shfmtArgs, shellScriptsDir))
  inputs.files(shellScriptFiles.map { file(it) })
  outputs.upToDateWhen { true }
}

tasks.register<Exec>("shfmtApply") {
  group = "verification"
  description = "shfmt でシェルスクリプトを整形します。"
  commandLine(dockerRun(shfmtImage, "-w", *shfmtArgs, shellScriptsDir))
}

tasks.named("check") {
  dependsOn("shellcheck", "shfmtCheck")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

val prettierPackages = mapOf("prettier" to "3.6.2")

val sqlPrettierPackages = prettierPackages + mapOf("prettier-plugin-sql" to "0.19.1")

// prettier-plugin-sql は sql-formatter を包んでいる。識別子は原文のまま残し、キーワードだけ大文字に揃える。
val sqlPrettierConfig =
  mapOf(
    "plugins" to listOf("prettier-plugin-sql"),
    "parser" to "sql",
    "language" to "postgresql",
    "keywordCase" to "upper",
    "dataTypeCase" to "upper",
    "functionCase" to "upper",
    "identifierCase" to "preserve",
    "tabWidth" to 4,
  )

// node_modules や dist、Playwrightの出力など、npmやツールが生成したファイルは整形しない。
val generatedWebFiles =
  arrayOf(
    "**/node_modules/**",
    "frontend/dist/**",
    "e2e/playwright-report/**",
    "e2e/test-results/**",
  )

spotless {
  kotlin {
    target("src/**/*.kt")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }

  kotlinGradle {
    target("*.gradle.kts", "gradle/**/*.gradle.kts")
    ktfmt().googleStyle()
    trimTrailingWhitespace()
    endWithNewline()
  }

  format("frontend") {
    target(
      "frontend/**/*.ts",
      "frontend/**/*.vue",
      "frontend/**/*.js",
      "frontend/**/*.css",
      "frontend/**/*.html",
    )
    targetExclude(*generatedWebFiles)
    prettier(prettierPackages).configFile(rootProject.file(".prettierrc.json"))
  }

  format("e2e") {
    target("e2e/**/*.ts")
    targetExclude(*generatedWebFiles)
    prettier(prettierPackages).configFile(rootProject.file(".prettierrc.json"))
  }

  // 注意: Flywayは適用済みマイグレーションの内容からチェックサムを取る。整形で1バイトでも変われば、
  // 既に適用済みのDBは checksum mismatch で起動しなくなる。既存のマイグレーションを整形し直したときは、
  // DBを作り直すか `flyway repair` でチェックサムを打ち直すこと。
  sql {
    target("src/main/resources/db/migration/*.sql")
    prettier(sqlPrettierPackages).config(sqlPrettierConfig)
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}
