# DivalHR Core API image. Base images pinned by version and digest.
# Java 21 LTS per ADR 0004; RuntimeBaselineTest fails if this drifts to another major version.
FROM eclipse-temurin:21.0.12.1_1-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026 AS build
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

FROM eclipse-temurin:21.0.12.1_1-jre@sha256:d7051a45dd955e4d5d1db4d3f4269fe13d1c6dff8cc6b7ef89fc8577b96c1982
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
