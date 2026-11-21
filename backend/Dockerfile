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
# Heap as a share of the container's memory, not a fixed number.
#
# This was -Xmx320m, sized for a 512MB host. The reasoning was sound - the heap is only
# part of the JVM's footprint, and metaspace, thread stacks, GC structures and direct
# buffers all live outside it - but the figure was absolute, so a host with 4GB ran the
# same 320MB heap as one with 512MB. On a historical backfill that is the difference
# between importing a few hundred thousand candles comfortably and spending the run in
# garbage collection: the fetch is paced at two requests a second whatever the hardware,
# so any time lost to GC is time added to a job already measured in hours.
#
# MaxRAMPercentage is container-aware (the JVM reads the cgroup limit, not the host's
# RAM), so one setting is right everywhere. 60% is deliberately conservative: on a 512MB
# container it yields ~307MB, within a whisker of the 320MB this replaces, so the small
# host it was tuned for is no worse off, while a 2GB host gets ~1.2GB and a 4GB host
# ~2.4GB without anyone editing this file.
#
# The collector is left to ergonomics rather than pinned to SerialGC. The JVM already
# picks SerialGC on a small, few-CPU container and G1 on a larger one - which is the rule
# the old comment was applying by hand, except that pinning it meant a well-provisioned
# server was stuck with the small-host choice too.
#
# Metaspace raised to 256m: this is a large Spring context with JPA, and 128m was close
# enough to the edge that a class-loading spike could trip ExitOnOutOfMemoryError, which
# restarts the container and surfaces as a fetch that stopped for no visible reason.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -Xss512k -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
