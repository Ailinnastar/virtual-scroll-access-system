FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew --no-daemon build installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV VSAS_PRODUCTION=true VSAS_DATA_DIR=/app/data
COPY --from=build /workspace/build/install/vsas/ ./
RUN mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/vsas"]
