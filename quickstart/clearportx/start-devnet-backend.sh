#!/bin/bash
set -e

cd /root/cn-quickstart/quickstart

echo "🚀 Starting ClearportX Backend for Canton Network DevNet..."

# Set environment
export SPRING_PROFILES_ACTIVE=devnet
export CANTON_LEDGER_HOST=localhost
export CANTON_LEDGER_PORT=5001

# Kill any existing backend
pkill -f "backend.jar" || true
sleep 2

# Start backend
java -jar backend/build/libs/backend.jar > /tmp/backend-devnet.log 2>&1 &
BACKEND_PID=$!

echo "Backend PID: $BACKEND_PID"
echo "Waiting for backend to start..."

# Wait for health endpoint
for i in {1..30}; do
    if curl -fsS http://localhost:8080/api/health/ledger 2>/dev/null | grep -q "status"; then
        echo "✅ Backend is healthy!"
        curl -s http://localhost:8080/api/health/ledger | jq .
        echo ""
        echo "🎉 Backend running on Canton Network DevNet!"
        echo "📊 Check logs: tail -f /tmp/backend-devnet.log"
        exit 0
    fi
    echo "Waiting... ($i/30)"
    sleep 2
done

echo "❌ Backend failed to start. Check logs:"
tail -50 /tmp/backend-devnet.log
exit 1
