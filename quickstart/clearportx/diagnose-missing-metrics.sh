#!/bin/bash
echo "=== Diagnosing Missing Metrics ==="
echo ""

echo "1. Checking all registered clearportx metrics:"
curl -s http://localhost:8080/api/actuator/metrics | jq -r '.names[] | select(contains("clearportx"))' | sort

echo ""
echo "2. Checking metrics with data:"
echo ""
echo "swap.executed.total:"
curl -s http://localhost:8080/api/actuator/metrics/clearportx.swap.executed.total | jq '{measurements: .measurements, tags: .availableTags}'

echo ""
echo "swap.executed.by_pair:"
curl -s http://localhost:8080/api/actuator/metrics/clearportx.swap.executed.by_pair | jq '{measurements: .measurements, tags: .availableTags}'

echo ""
echo "pool.active.count:"
curl -s http://localhost:8080/api/actuator/metrics/clearportx.pool.active.count | jq '{measurements: .measurements, tags: .availableTags}'

echo ""
echo "swap.input.amount:"
curl -s http://localhost:8080/api/actuator/metrics/clearportx.swap.input.amount | jq '{measurements: .measurements, tags: .availableTags}'

echo ""
echo "3. Checking backend logs for metric recording:"
docker logs backend-service --since 2m 2>&1 | grep -i "recording\|swapmetrics\|pool.*active" | head -10

