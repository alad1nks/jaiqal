FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app app
COPY core core
COPY server server
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre-jammy
RUN useradd --create-home --uid 10001 jaiqal
WORKDIR /app
COPY --from=build --chown=jaiqal:jaiqal /workspace/server/build/install/server/ ./
USER jaiqal
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
