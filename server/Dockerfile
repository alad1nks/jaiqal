FROM eclipse-temurin:22-jdk-jammy@sha256:d8e6ba486df17bf758888d2b1b608133d1eedca8daf69d3fc6bf78d8be81e07e AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app app
COPY core core
COPY feature feature
COPY resources resources
COPY server server
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:22-jre-jammy@sha256:dbcae8b5dd4d63f81739a538ec2c09797735f04a21d814f9071b62f018326043
RUN useradd --create-home --uid 10001 jaiqal
WORKDIR /app
COPY --from=build --chown=jaiqal:jaiqal /workspace/server/build/install/server/ ./
USER jaiqal
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
