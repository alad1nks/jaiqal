FROM eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8 AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app app
COPY core core
COPY feature feature
COPY resources resources
COPY server server
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre-jammy@sha256:3097cbbebb7d490494a98aed2301f284b38f79eba158eef098c6fc8c8af11c23
RUN useradd --create-home --uid 10001 jaiqal
WORKDIR /app
COPY --from=build --chown=jaiqal:jaiqal /workspace/server/build/install/server/ ./
USER jaiqal
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
