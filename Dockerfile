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

WORKDIR /app

COPY --from=backend-build /workspace/build/libs/*.jar app.jar

ARG APP_PORT=8080

EXPOSE ${APP_PORT}

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
