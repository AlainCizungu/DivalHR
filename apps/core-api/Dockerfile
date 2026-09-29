# DivalHR Core API image. Base images pinned by version and digest.
FROM eclipse-temurin:24.0.2_12-jdk@sha256:7493205ffe6caa8074fa8a06a276bb1c5ac41d3dd0fd43a0db66d7f776e80b3e AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --version
COPY config ./config
COPY src ./src
# Tests run in CI and via scripts/dev/verify-on-host.sh (they need Docker for Testcontainers).
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/core-api-*.jar /app.jar
COPY docker/HealthCheck.java /tmp/HealthCheck.java
RUN javac -d /healthcheck /tmp/HealthCheck.java

FROM eclipse-temurin:24.0.2_12-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2
RUN groupadd --system --gid 10001 divalhr && useradd --system --uid 10001 --gid divalhr --no-create-home divalhr
WORKDIR /app
COPY --from=build --chown=divalhr:divalhr /app.jar /app/app.jar
COPY --from=build --chown=divalhr:divalhr /healthcheck /app/healthcheck
USER 10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=12 \
  CMD ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xmx32m", "-cp", "/app/healthcheck", "HealthCheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
