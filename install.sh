#!/bin/bash
# =============================================================================
# TradeRakshak(tm) v11.5 — No-Docker Installer for Linux/Mac
# Runs entirely without Docker or system-wide installs
# Downloads portable JDK 21 into the app folder only
# =============================================================================

set -e
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="$APP_DIR/install_log.txt"
JAVA_PORTABLE="$APP_DIR/jdk21"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

log()  { echo -e "${GREEN}[OK]${RESET} $*" | tee -a "$LOG_FILE"; }
warn() { echo -e "${YELLOW}[!!]${RESET} $*" | tee -a "$LOG_FILE"; }
err()  { echo -e "${RED}[ERROR]${RESET} $*" | tee -a "$LOG_FILE"; }
info() { echo -e "${CYAN}[..]${RESET} $*" | tee -a "$LOG_FILE"; }

clear
echo -e "${BOLD}"
echo "  ╔══════════════════════════════════════════════════════════╗"
echo "  ║   TradeRakshak™ v11.5 — No-Docker Installer            ║"
echo "  ║   No Docker required. No system Java needed.            ║"
echo "  ╚══════════════════════════════════════════════════════════╝"
echo -e "${RESET}"
echo ""
echo "  !! NOT a SEBI-registered Investment Advisor !!"
echo "  !! For educational purposes only             !!"
echo ""

echo "Install started: $(date)" > "$LOG_FILE"

# ── Create required folders ──────────────────────────────────────────────────
mkdir -p "$APP_DIR"/{data,uploads,exports,backups,logs}

# ── Create .env if missing ───────────────────────────────────────────────────
if [ ! -f "$APP_DIR/.env" ]; then
    RAND=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | head -c 24 2>/dev/null || date +%s%N | md5sum | head -c 24)
    cat > "$APP_DIR/.env" << ENVEOF
SPRING_PROFILE=dev
DATABASE_URL=jdbc:sqlite:./data/app.db
DB_DRIVER=org.sqlite.JDBC
JWT_SECRET=TR2026-${RAND}-SecureKey
FERNET_KEY=
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=
SMTP_PASSWORD=
TELEGRAM_BOT_TOKEN=
UPLOAD_DIR=./uploads
EXPORT_DIR=./exports
BACKUP_DIR=./backups
DATA_DIR=./data
LOG_LEVEL=INFO
PORT=8080
CORS_ORIGINS=http://localhost:8080
ENVEOF
    log ".env created with secure auto-generated key"
fi

# ── Check internet ─────────────────────────────────────────────────────────────
info "Checking internet connection..."
if ! ping -c1 -W3 8.8.8.8 >/dev/null 2>&1 && ! ping -c1 -W3 1.1.1.1 >/dev/null 2>&1; then
    err "No internet connection."
    echo "  Internet is needed for first-time download of Java and libraries."
    echo "  After first build, app runs OFFLINE forever."
    exit 1
fi
log "Internet connection OK"

# ── Check / install portable Java 21 ─────────────────────────────────────────
JAVA_EXE="java"
if [ -x "$JAVA_PORTABLE/bin/java" ]; then
    JAVA_EXE="$JAVA_PORTABLE/bin/java"
    export JAVA_HOME="$JAVA_PORTABLE"
    log "Portable Java 21 already installed"
elif java -version 2>&1 | grep -q '"21'; then
    log "System Java 21 found"
else
    info "Java 21 not found. Downloading portable JDK 21 (~180 MB, one-time only)..."
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m)
    
    if [ "$OS" = "darwin" ]; then
        [ "$ARCH" = "arm64" ] && JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.4+7/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.4_7.tar.gz" \
                               || JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.4+7/OpenJDK21U-jdk_x64_mac_hotspot_21.0.4_7.tar.gz"
    else
        JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.4+7/OpenJDK21U-jdk_x64_linux_hotspot_21.0.4_7.tar.gz"
    fi
    
    JDK_TGZ="$APP_DIR/jdk21.tar.gz"
    curl -L -o "$JDK_TGZ" "$JDK_URL" --progress-bar 2>&1 || wget -O "$JDK_TGZ" "$JDK_URL" --show-progress 2>&1
    
    mkdir -p "$JAVA_PORTABLE"
    tar -xzf "$JDK_TGZ" -C "$JAVA_PORTABLE" --strip-components=1
    rm -f "$JDK_TGZ"
    
    JAVA_EXE="$JAVA_PORTABLE/bin/java"
    export JAVA_HOME="$JAVA_PORTABLE"
    log "Java 21 installed to $JAVA_PORTABLE"
fi

# ── Check if JAR already built ───────────────────────────────────────────────
JAR_FILE=$(ls "$APP_DIR/backend/target/traderakshak-backend-*.jar" 2>/dev/null | head -1)

if [ -n "$JAR_FILE" ]; then
    log "Application already built: $JAR_FILE"
else
    info "Building application (first time: 10-30 min depending on internet speed)..."
    echo ""
    echo "  You will see many 'Downloading...' messages. This is normal."
    echo "  Do NOT close this window."
    echo ""

    cd "$APP_DIR/backend"
    export PATH="$JAVA_HOME/bin:$PATH"
    
    if [ -x "./mvnw" ]; then
        chmod +x mvnw
        ./mvnw clean package -DskipTests --no-transfer-progress
    else
        mvn clean package -DskipTests --no-transfer-progress
    fi
    cd "$APP_DIR"
    
    JAR_FILE=$(ls "$APP_DIR/backend/target/traderakshak-backend-*.jar" 2>/dev/null | head -1)
    if [ -z "$JAR_FILE" ]; then
        err "Build failed. Check install_log.txt for details."
        exit 1
    fi
    log "Build successful: $JAR_FILE"
fi

# ── Read PORT from .env ───────────────────────────────────────────────────────
APP_PORT=$(grep "^PORT=" "$APP_DIR/.env" 2>/dev/null | cut -d'=' -f2 || echo "8080")
APP_PORT=${APP_PORT:-8080}

# ── Create start/stop scripts ────────────────────────────────────────────────
cat > "$APP_DIR/start.sh" << STARTEOF
#!/bin/bash
cd "\$(dirname "\$0")"
APP_DIR="\$(pwd)"
JAVA_EXE="\$APP_DIR/jdk21/bin/java"
[ ! -x "\$JAVA_EXE" ] && JAVA_EXE="java"
JAR=\$(ls "\$APP_DIR/backend/target/traderakshak-backend-*.jar" 2>/dev/null | head -1)
[ -z "\$JAR" ] && echo "Run install.sh first" && exit 1
source "\$APP_DIR/.env" 2>/dev/null || true
echo ""
echo "  TradeRakshak™ v11.5 starting..."
echo "  Open: http://localhost:\${PORT:-8080}"
echo "  Login: superadmin@traderakshak.local / Admin@123"
echo "  Press Ctrl+C to stop"
echo ""
"\$JAVA_EXE" -Xms256m -Xmx768m -jar "\$JAR"
STARTEOF
chmod +x "$APP_DIR/start.sh"

cat > "$APP_DIR/stop.sh" << STOPEOF
#!/bin/bash
echo "Stopping TradeRakshak..."
pkill -f "traderakshak-backend" 2>/dev/null && echo "Stopped." || echo "Not running."
STOPEOF
chmod +x "$APP_DIR/stop.sh"

# ── Launch ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}"
echo "  ╔══════════════════════════════════════════════════════════╗"
echo "  ║   BUILD COMPLETE! TradeRakshak is starting...           ║"
echo "  ║                                                          ║"
echo "  ║   Open browser at:  http://localhost:${APP_PORT}               ║"
echo "  ║                                                          ║"
echo "  ║   Login credentials:                                     ║"
echo "  ║   Email    : superadmin@traderakshak.local              ║"
echo "  ║   Password : Admin@123                                   ║"
echo "  ║                                                          ║"
echo "  ║   DO NOT close this terminal while app is running.      ║"
echo "  ║   To stop: press Ctrl+C or run ./stop.sh                ║"
echo "  ╚══════════════════════════════════════════════════════════╝"
echo -e "${RESET}"

# Auto-open browser after 20 seconds
(sleep 20 && command -v xdg-open >/dev/null && xdg-open "http://localhost:$APP_PORT" 2>/dev/null \
          || open "http://localhost:$APP_PORT" 2>/dev/null || true) &

# Load env and start
set -a; source "$APP_DIR/.env" 2>/dev/null || true; set +a
"$JAVA_EXE" -Xms256m -Xmx768m -jar "$JAR_FILE"
