#!/usr/bin/env bash
# Script to seed or clear live alerts for demonstrations
set -e

ACTION="${1:-seed}"

if [ "$ACTION" = "clear" ]; then
  echo "Closing all open alerts..."
  docker compose exec -T postgres psql -U api_analytics -d api_analytics -c "UPDATE alerts SET closed_at = NOW() WHERE closed_at IS NULL;"
  echo "✓ All alerts marked as resolved/closed."
elif [ "$ACTION" = "seed" ]; then
  echo "Seeding live alerts for presentation..."
  docker compose exec -T postgres psql -U api_analytics -d api_analytics <<'SQL'
INSERT INTO alerts (id, service_id, triggering_metric, severity, priority, message, opened_at, closed_at)
VALUES 
(
  gen_random_uuid(),
  '11111111-1111-1111-1111-111111111111',
  'latency',
  'critical',
  'critical',
  'P99 latency 4,820 ms exceeds threshold 2,000 ms (Payments Gateway)',
  NOW(),
  NULL
),
(
  gen_random_uuid(),
  '33333333-3333-3333-3333-333333333333',
  'error_rate',
  'high',
  'high',
  'Error rate 24.5% exceeds threshold 10.0% (Orders Service 503)',
  NOW(),
  NULL
),
(
  gen_random_uuid(),
  '22222222-2222-2222-2222-222222222222',
  'availability',
  'medium',
  'medium',
  'Inventory service heartbeat delayed > 15s',
  NOW(),
  NULL
);
SQL
  echo "✓ 3 Live alerts seeded! Refresh http://localhost:5173 to see them under 'Open alerts'."
else
  echo "Usage: ./seed_alerts.sh [seed|clear]"
  exit 1
fi
