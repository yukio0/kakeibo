FROM node:24-alpine AS frontend-build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./

RUN npm ci

COPY frontend .

RUN npm run build

FROM eclipse-temurin:21-jdk-jammy AS backend-build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./

RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies

COPY src src

COPY --from=frontend-build /workspace/frontend/dist src/main/resources/static

RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 app \
  && useradd --system --uid 10001 --gid app app

WORKDIR /app

COPY --from=backend-build --chown=app:app /workspace/build/libs/*.jar app.jar

USER 10001

ARG APP_PORT=8080

EXPOSE ${APP_PORT}

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
