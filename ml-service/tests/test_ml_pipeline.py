"""Tests for canonical ML feature pipelines, validation, model modes, and scheduling."""
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pandas as pd
from fastapi.testclient import TestClient

from app.features.pipeline import (
    SNAPSHOT_FEATURE_COLUMNS,
    SPEC,
    build_snapshot_feature_frame,
    engineer_snapshot_row,
)
from app.main import app

client = TestClient(app)


def _sample_snapshot(offset_minutes: int = 0) -> dict:
    return {
        "service_id": str(uuid4()),
        "timestamp": (datetime.now(UTC) - timedelta(minutes=offset_minutes)).isoformat(),
        "request_count": 150,
        "response_time_mean": 110.0,
        "response_time_p95": 210.0,
        "response_time_p99": 310.0,
        "error_rate": 0.02,
        "cpu_usage_pct": 45.5,
        "memory_usage_pct": 60.0,
    }


def test_snapshot_feature_pipeline_consistency():
    snap = _sample_snapshot()
    row_df = engineer_snapshot_row(snap)
    assert list(row_df.columns) == SNAPSHOT_FEATURE_COLUMNS

    # Also test batch frame
    df_raw = pd.DataFrame([snap])
    df_raw["window_start"] = df_raw["timestamp"]
    batch_df = build_snapshot_feature_frame(df_raw)
    for col in SNAPSHOT_FEATURE_COLUMNS:
        assert col in batch_df.columns
        assert abs(row_df[col].iloc[0] - batch_df[col].iloc[0]) < 1e-5


def test_anomaly_inference_uses_snapshot_features():
    snap = _sample_snapshot()
    body = {
        "service_id": snap["service_id"],
        "snapshot": snap,
    }
    r = client.post("/predict/anomaly", json=body)
    assert r.status_code == 200
    data = r.json()
    assert 0.0 <= data["anomaly_score"] <= 1.0
    assert isinstance(data["is_anomaly"], bool)
    assert data["model_mode"] in {"trained", "heuristic"}


def test_traffic_insufficient_history_returns_400():
    # Only 25 snapshots (less than 1455 required)
    history = [_sample_snapshot(i) for i in range(25)]
    body = {
        "service_id": str(uuid4()),
        "horizon_minutes": 30,
        "history": history,
    }
    r = client.post("/predict/traffic", json=body)
    # If trained model is loaded, it must reject with 400 Bad Request, NOT 500
    if r.status_code != 200:
        assert r.status_code == 400
        assert "Insufficient history" in r.json()["detail"]
    else:
        # Heuristic fallback returned
        assert r.json()["model_mode"] == "heuristic"


def test_failure_insufficient_history_returns_400():
    # Only 30 snapshots (less than 1455 required)
    history = [_sample_snapshot(i) for i in range(30)]
    body = {
        "service_id": str(uuid4()),
        "history": history,
    }
    r = client.post("/predict/failure", json=body)
    if r.status_code != 200:
        assert r.status_code == 400
        assert "Insufficient history" in r.json()["detail"]
    else:
        assert r.json()["model_mode"] == "heuristic"


def test_healthz_and_readyz_contain_model_modes():
    r = client.get("/healthz")
    assert r.status_code == 200
    j = r.json()
    assert "model_modes" in j
    assert j["model_modes"]["health"] == "rule_based"

    r = client.get("/readyz")
    assert r.status_code == 200
    j = r.json()
    assert "model_modes" in j
    for m in ("traffic", "failure", "anomaly", "alerts", "health"):
        assert m in j["model_modes"]


def test_admin_scheduler_endpoints(monkeypatch):
    async def mock_retraining_job():
        pass

    monkeypatch.setattr("app.main.run_retraining_job", mock_retraining_job)

    r = client.get("/admin/scheduler", headers={"X-Admin-Key": "dev_admin_key_secret"})
    assert r.status_code == 200
    j = r.json()
    assert "running" in j
    assert "cron" in j

    r = client.post("/admin/retrain", headers={"X-Admin-Key": "dev_admin_key_secret"})
    assert r.status_code == 200
    assert r.json()["status"] == "triggered"
