# === STAGE 1: Build stage ===
FROM gradle:8.7-jdk17 AS build
WORKDIR /app

# Proxy-Umgebungsvariablen (werden durch docker-compose gesetzt)
ARG http_proxy
ARG https_proxy
ARG no_proxy
ENV http_proxy=${http_proxy}
ENV https_proxy=${https_proxy}
ENV no_proxy=${no_proxy}

# Cache Layer vorbereiten
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN gradle clean build -x test --no-daemon || true

# Quellcode kopieren und bauen
COPY . .
RUN gradle clean build -x test --no-daemon

# === STAGE 2: Runtime stage ===
FROM eclipse-temurin:17-jre
WORKDIR /app

# Proxy auch hier (für Java / REST-Aufrufe im Betrieb)
ARG http_proxy
ARG https_proxy
ARG no_proxy
ENV http_proxy=${http_proxy}
ENV https_proxy=${https_proxy}
ENV no_proxy=${no_proxy}

# App kopieren
COPY --from=build /app/build/libs/*-all.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
