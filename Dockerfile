# ---------------------------------------------------------------------------
# Merqo backend image.
#
# The hosting platform builds this file; the student never runs Docker locally.
# The result is a plain executable jar that also runs on any JDK 21 host.
# ---------------------------------------------------------------------------

# ---- Build stage ------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Copy the POM first so dependency download is cached between code changes.
COPY backend/pom.xml ./backend/pom.xml
RUN mvn -f backend/pom.xml -B -q dependency:go-offline || true

COPY backend ./backend
RUN mvn -f backend/pom.xml -B -DskipTests package

# ---- Runtime stage ----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Run as a non root user. The free Render instance gives us a non root user
# anyway, but this keeps the image safe to reuse anywhere.
RUN addgroup -S merqo && adduser -S merqo -G merqo

WORKDIR /app
COPY --from=build /build/backend/target/merqo-backend-*.jar app.jar
RUN chown -R merqo:merqo /app
USER merqo

# Render injects PORT; the default keeps `docker run` simple.
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:${PORT}/api/v1/health/ping || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
