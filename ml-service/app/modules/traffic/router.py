"""Module 1 — traffic forecasting (XGBoost Regressor).

Uses a trained per-horizon XGBoost model if available. Falls back to a
naive last-observed baseline while the model artifact is missing so the
contract shape stays intact for downstream callers.
"""
from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter, HTTPException

from app.features import SPEC, engineer_row
from app.kafka import publish_prediction
from app.schemas import TrafficPredictionRequest, TrafficPredictionResponse
from app.training import registry

router = APIRouter(prefix="/predict", tags=["traffic"])


@router.post("/traffic", response_model=TrafficPredictionResponse)
async def predict_traffic(req: TrafficPredictionRequest) -> TrafficPredictionResponse:
    bundle = registry.get("traffic")

    def _resp(
        predicted_rpm: float,
        version: str,
        model_mode: Literal["trained", "heuristic"] = "trained",
    ) -> TrafficPredictionResponse:
        r = TrafficPredictionResponse(
            service_id=req.service_id,
            horizon_minutes=req.horizon_minutes,
            predicted_rpm=max(0.0, predicted_rpm),
            model_version=version,
            model_mode=model_mode,
            generated_at=datetime.now(UTC),
        )
        publish_prediction("traffic", req.service_id, r.model_dump(mode="json"))
        return r

    if bundle is None:
        last = req.history[-1]
        return _resp(float(last.request_count), "stub-0.0.0", model_mode="heuristic")

    key = f"h_{req.horizon_minutes}"
    models = bundle["model"]
    if key not in models:
        last = req.history[-1]
        return _resp(float(last.request_count), bundle["meta"]["version"], model_mode="heuristic")

    required_history = SPEC.required_history_minutes()
    if len(req.history) < required_history:
        raise HTTPException(
            status_code=400,
            detail=(
                f"Insufficient history: trained sequence model requires at least {required_history} "
                f"minutes of observation history, but received {len(req.history)}"
            ),
        )

    try:
        history_dicts = [s.model_dump(mode="python") for s in req.history]
        for h in history_dicts:
            h["service_id"] = str(h["service_id"])
        row = engineer_row(history_dicts)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    X = bundle["scaler"].transform(row.values)
    prediction = float(models[key].predict(X)[0])
    return _resp(prediction, bundle["meta"]["version"], model_mode="trained")
