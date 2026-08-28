# =============================================================================
# TradeRakshak(tm) Backend Dockerfile
# =============================================================================

FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -q 2>/dev/null || true

COPY src ./src

# Run with full output so errors are visible
RUN mvn clean package -DskipTests 2>&1

# Stage 2 - Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S traderakshak && adduser -S traderakshak -G traderakshak
COPY --from=builder /build/target/*.jar app.jar
RUN mkdir -p /app/data /app/uploads /app/exports /app/backups && \
    chown -R traderakshak:traderakshak /app

USER traderakshak
# Sized for a 512MB host: the heap is only part of the JVM's footprint - metaspace,
# thread stacks, GC structures and direct buffers all live outside it, so -Xmx512m on a
# 512MB box leaves nothing for them. SerialGC because G1's region bookkeeping is not
# worth it below ~1GB, and a 512k thread stack since none of this code recurses deeply.
ENV JAVA_OPTS="-Xms160m -Xmx320m -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
