#!/bin/bash
set -e

# Step 1 setup
export JWT_SECRET="local-dev-secret-min-32-chars-required-here"
export POSTGRES_HOST=localhost POSTGRES_PORT=5435
export REDIS_HOST=localhost REDIS_PORT=6381
export KAFKA_BOOTSTRAP_SERVERS=localhost:29093
export AUTH_HOST=localhost REGISTRY_HOST=localhost
export ANALYTICS_HOST=localhost DASHBOARD_HOST=localhost ML_HOST=localhost
export PORT_ML=8006

# Clean up any lingering demo service processes on target ports
fuser -k 8080/tcp 8081/tcp 8082/tcp 8083/tcp 8084/tcp 8085/tcp 8006/tcp 5173/tcp 2>/dev/null || true

echo "Step 2: Start infrastructure (Postgres, Redis, Kafka)"
docker compose up -d postgres redis kafka zookeeper
docker compose up kafka-init

echo "Step 3: Build backend"
cd backend && mvn -DskipTests install && cd ..

echo "Step 4: Generate synthetic data"
cd synthetic-data
python3 -m venv .venv && source .venv/bin/activate
pip install -q -e .
synth clear --since 2020-01-01
synth generate --days 30 --seed 42
deactivate && cd ..

echo "Step 5: Train ML models"
cd ml-service
python3 -m venv .venv && source .venv/bin/activate
pip install -q -e ".[dev]"
export ML_MODEL_DIR="$(pwd)/app/models"
python -m app.training.cli --days 30
deactivate && cd ..

echo "Step 6: Start services"
JAVA="java"
nohup "$JAVA" -jar backend/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar > /tmp/auth.log 2>&1 &
nohup "$JAVA" -jar backend/registry-service/target/registry-service-0.1.0-SNAPSHOT.jar > /tmp/registry.log 2>&1 &
nohup "$JAVA" -jar backend/metrics-collector/target/metrics-collector-0.1.0-SNAPSHOT.jar > /tmp/collector.log 2>&1 &
nohup "$JAVA" -jar backend/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar > /tmp/gateway.log 2>&1 &
nohup "$JAVA" -jar backend/analytics-service/target/analytics-service-0.1.0-SNAPSHOT.jar > /tmp/analytics.log 2>&1 &
nohup "$JAVA" -jar backend/dashboard-service/target/dashboard-service-0.1.0-SNAPSHOT.jar > /tmp/dashboard.log 2>&1 &

cd ml-service && source .venv/bin/activate
export ML_MODEL_DIR="$(pwd)/app/models"
nohup uvicorn app.main:app --host 0.0.0.0 --port 8006 > /tmp/ml.log 2>&1 &
deactivate && cd ..

sleep 20

echo "Step 7: Seed live alerts"
docker compose exec -T postgres psql -U api_analytics -d api_analytics <<'SQL'
INSERT INTO metrics (service_id, window_start, window_end, window_size,
request_count, rpm, response_time_mean, response_time_p95,
response_time_p99, error_rate, success_rate, computed_at)
VALUES
('11111111-1111-1111-1111-111111111111',
date_trunc('minute', NOW()), date_trunc('minute', NOW()) + interval '1 minute',
'1m', 500, 500, 250, 800, 4500, 0.15, 0.85, NOW()),
('33333333-3333-3333-3333-333333333333',
date_trunc('minute', NOW()), date_trunc('minute', NOW()) + interval '1 minute',
'1m', 200, 200, 400, 900, 2100, 0.18, 0.82, NOW())
ON CONFLICT (service_id, window_size, window_start) DO UPDATE
SET response_time_p99 = EXCLUDED.response_time_p99,
error_rate = EXCLUDED.error_rate,
computed_at = NOW();
SQL

echo "Step 8: Start frontend"
cd frontend && nohup python3 -m http.server 5173 > /dev/null 2>&1 &
echo "Frontend started on port 5173"
