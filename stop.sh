#!/bin/bash
echo "Stopping TradeRakshak(tm)..."
cd "$(dirname "$0")"
docker compose down 2>/dev/null || docker-compose down
echo "Stopped. Your data is saved."
