#!/bin/bash
echo ""
echo "  Starting TradeRakshak(tm) v11.4..."
echo "  Open your browser to: http://localhost"
echo "  (Wait 30-60 seconds)"
echo "  Login: superadmin@traderakshak.local / Admin@123"
echo ""
cd "$(dirname "$0")"
docker compose up 2>/dev/null || docker-compose up
