# DivalHR Core API image. Base images pinned by version and digest.
FROM eclipse-temurin:21.0.12.1_1-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --version
COPY config ./config
COPY src ./src
# Tests run in CI and via scripts/dev/verify-on-host.sh (they need Docker for Testcontainers).
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/core-api-*.jar /app.jar

FROM eclipse-temurin:21.0.12.1_1-jre@sha256:d7051a45dd955e4d5d1db4d3f4269fe13d1c6dff8cc6b7ef89fc8577b96c1982
RUN groupadd --system --gid 10001 divalhr && useradd --system --uid 10001 --gid divalhr --no-create-home divalhr
WORKDIR /app
COPY --from=build --chown=divalhr:divalhr /app.jar /app/app.jar
USER 10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3 && grep -q "\"status\":\"UP\"" <&3'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
