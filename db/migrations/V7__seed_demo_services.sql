-- V7: seed demo services for local dev.
-- Three synthetic APIs (used by the synthetic-data generator and ML training)
-- and four platform microservices so the gateway's ServiceResolver can
-- attribute telemetry correctly.

-- ─── Synthetic monitored APIs ─────────────────────────────────────────
INSERT INTO services (id, name, base_url, owner, environment,
                      description, capture_telemetry)
VALUES
  ('11111111-1111-1111-1111-111111111111',
   'payments-api', 'http://payments.internal', 'platform',
   'development', 'Synthetic payments API for demo and training data.', TRUE),
  ('22222222-2222-2222-2222-222222222222',
   'inventory-api', 'http://inventory.internal', 'platform',
   'development', 'Synthetic inventory API for demo and training data.', TRUE),
  ('33333333-3333-3333-3333-333333333333',
   'orders-api', 'http://orders.internal', 'platform',
   'development', 'Synthetic orders API for demo and training data.', TRUE)
ON CONFLICT (name, environment) DO NOTHING;

-- ─── Platform microservices ───────────────────────────────────────────
INSERT INTO services (id, name, base_url, owner, environment,
                      description, capture_telemetry)
VALUES
  ('a0000001-0000-0000-0000-000000000001',
   'auth-service', 'http://auth-service:8081', 'platform',
   'development', 'Authentication and JWT token service.', TRUE),
  ('a0000002-0000-0000-0000-000000000002',
   'registry-service', 'http://registry-service:8082', 'platform',
   'development', 'API registry — CRUD for monitored services.', TRUE),
  ('a0000003-0000-0000-0000-000000000003',
   'analytics-service', 'http://analytics-service:8084', 'platform',
   'development', 'Metric aggregation and alerting.', TRUE),
  ('a0000004-0000-0000-0000-000000000004',
   'dashboard-service', 'http://dashboard-service:8085', 'platform',
   'development', 'Dashboard aggregation with ML circuit-breaker.', TRUE)
ON CONFLICT (name, environment) DO NOTHING;
