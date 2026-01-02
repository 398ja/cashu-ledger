FROM eclipse-temurin:21-jre

LABEL maintainer="tcheeric" \
      description="Cashu Ledger Web - Voucher inspection service with nostrdb caching"

# Install curl for health check
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Create directory for persistent event cache (nostrdb)
RUN mkdir -p /app/data/ndb && chown -R 1000:1000 /app/data

COPY target/cashu-ledger-web-*.jar app.jar

# Volume for persistent nostrdb cache
VOLUME /app/data

EXPOSE 6060

# Health check using Spring Boot actuator
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:6060/actuator/health || exit 1

# JVM flags for container environments
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

# Default storage path inside container
ENV LEDGER_WEB_STORAGE_PATH=/app/data/ndb

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
