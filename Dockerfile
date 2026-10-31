FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package \
    && java -Djarmode=tools -jar target/storesync-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine
# Pick up Alpine security fixes released after the base image was built.
RUN apk upgrade --no-cache \
    && addgroup -S storesync && adduser -S -G storesync -u 10001 storesync
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
ENV STORESYNC_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=12 \
    CMD wget -qO- "http://localhost:${STORESYNC_PORT}/actuator/health/readiness" | grep -q UP || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
