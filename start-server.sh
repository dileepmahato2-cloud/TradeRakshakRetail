#!/bin/bash
# ---------------------------------------------------------------------------
#  TradeRakshak - Linux / EC2 launcher, sized for a 2 GB box
#
#  Why this file exists: `java -jar traderakshak.jar` takes NO heap argument, so
#  the JVM falls back to its default maximum of one QUARTER of system RAM. On a
#  2 GB server that is a 512 MB ceiling, which is what the instrument refresh ran
#  into. The flags below state the budget explicitly instead of inheriting it.
#
#  The budget on a 2 GB box that also runs PostgreSQL:
#
#     PostgreSQL + its backends   ~500 MB
#     OS, sshd, agents            ~250 MB
#     JVM non-heap                ~250 MB   (metaspace, code cache, thread stacks)
#     JVM heap            -Xmx     768 MB
#                                 --------
#                                 ~1.77 GB, leaving headroom for page cache
#
#  Raising -Xmx past ~900m on this box does not buy anything: it takes the memory
#  from PostgreSQL's page cache, and a database that cannot cache its indexes will
#  cost more than the extra heap saves.
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

JAR="${JAR:-traderakshak.jar}"
[ -f "$JAR" ] || JAR="backend/target/$(ls -S backend/target 2>/dev/null | grep -E '^traderakshak-backend-.*\.jar$' | grep -v '\.original$' | head -1)"

if [ ! -f "$JAR" ]; then
  echo "  [!] No jar found. Set JAR=/path/to/traderakshak.jar or build first." >&2
  exit 1
fi

# Heap. Override with JAVA_HEAP_OPTS for a larger instance.
#   -Xms256m      start small; the heap grows on demand rather than reserving up front
#   -Xmx768m      the ceiling reasoned about above
#   -XX:+UseSerialGC
#                 on a heap this size the parallel collectors cost more than they save:
#                 G1 reserves region metadata and starts a worker per core (four here),
#                 each with its own stack and buffers. Serial has one, and with a sub-GB
#                 heap its pauses stay in the low tens of milliseconds.
#   -XX:MaxMetaspaceSize=192m
#                 metaspace is NOT part of -Xmx and has no default limit, so a leak there
#                 shows up as the kernel OOM-killing the process rather than as a Java error.
#   -Xss512k      this app runs many threads (live feed, schedulers, backfill workers);
#                 the 1 MB default stack each is real memory at that count.
JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS:--Xms256m -Xmx768m -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -Xss512k}"

# Fail loudly and leave evidence instead of dying silently mid-request.
JAVA_DIAG_OPTS="${JAVA_DIAG_OPTS:--XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs}"

# Connection pool, sized for this box. Each pooled connection is a forked PostgreSQL
# backend costing 5-10 MB server-side, so a big pool is paid for twice on a single host.
export DB_POOL_MAX="${DB_POOL_MAX:-6}"
export DB_POOL_MIN="${DB_POOL_MIN:-2}"

mkdir -p logs

echo "  Starting TradeRakshak"
echo "    jar       : $JAR"
echo "    heap      : $JAVA_HEAP_OPTS"
echo "    db pool   : max=$DB_POOL_MAX min=$DB_POOL_MIN"
echo "    profile   : ${SPRING_PROFILE:-dev}"
echo ""

exec java $JAVA_HEAP_OPTS $JAVA_DIAG_OPTS -jar "$JAR"
