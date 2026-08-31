FROM eclipse-temurin:25-jdk-jammy@sha256:89565961a318534f01c971c7b1d030e60713c66995b887c94010cef938dbc53e AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app app
COPY core core
COPY feature feature
COPY resources resources
COPY server server
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:25-jre-jammy@sha256:10c251954d0bfe1a59ba93505f8c628d755919412400aa98685764c9353605d6
RUN useradd --create-home --uid 10001 jaiqal
WORKDIR /app
COPY --from=build --chown=jaiqal:jaiqal /workspace/server/build/install/server/ ./
USER jaiqal
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
