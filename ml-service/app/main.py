"""FastAPI application entrypoint.

Routers for each module are wired here. Concrete inference and training
lives in ``app.modules.<name>``; this file is only composition and
cross-cutting concerns (health, admin, error handlers).
"""
from contextlib import asynccontextmanager
from datetime import UTC, datetime

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException

from app import __version__
from app.auth import verify_admin_key
from app.config import settings
from app.modules.alerts.router import router as alerts_router
from app.modules.anomaly.router import router as anomaly_router
from app.modules.failure.router import router as failure_router
from app.modules.health.router import router as health_router
from app.modules.traffic.router import router as traffic_router
from app.schemas import HealthStatus, ModuleName
from app.training import registry
from app.training.scheduler import (
    get_scheduler_status,
    run_retraining_job,
    shutdown_scheduler,
    start_scheduler,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Eagerly load any trained artifacts under ML_MODEL_DIR.
    for m in ("traffic", "failure", "anomaly", "alerts"):
        registry.get(m)
    # Start background scheduled retraining
    start_scheduler()
    yield
    shutdown_scheduler()


app = FastAPI(
    title="ML Prediction Service",
    version=__version__,
    description=(
        "Predictive monitoring endpoints for the AI-Powered API Performance "
        "Analytics Platform. Contract: docs/api-contracts/ml-service-openapi.yaml"
    ),
    lifespan=lifespan,
)


def _get_model_modes() -> dict[str, str]:
    modes = {}
    for m in ("traffic", "failure", "anomaly", "alerts"):
        modes[m] = "trained" if registry.get(m) is not None else "heuristic"
    modes["health"] = "rule_based"
    return modes


@app.get("/healthz", response_model=HealthStatus, tags=["health"])
async def healthz() -> HealthStatus:
    return HealthStatus(status="ok", version=__version__, model_modes=_get_model_modes())


@app.get("/readyz", response_model=HealthStatus, tags=["health"])
async def readyz() -> HealthStatus:
    loaded = [ModuleName(m["module"]) for m in registry.list_loaded()]
    # Health-scoring is deterministic — always "ready".
    loaded.append(ModuleName.HEALTH)
    return HealthStatus(
        status="ok",
        version=__version__,
        models_loaded=loaded,
        model_modes=_get_model_modes(),
    )


app.include_router(traffic_router)
app.include_router(failure_router)
app.include_router(anomaly_router)
app.include_router(health_router)
app.include_router(alerts_router)


@app.get("/admin/models", tags=["admin"], dependencies=[Depends(verify_admin_key)])
async def list_models() -> list[dict]:
    # Ensure every learnable module is at least attempted so the listing
    # reflects on-disk state, not just what's been called.
    for m in ("traffic", "failure", "anomaly", "alerts"):
        registry.get(m)
    return registry.list_loaded()


@app.post("/admin/models/{module}/reload", tags=["admin"], dependencies=[Depends(verify_admin_key)])
async def reload_model(module: ModuleName) -> dict:
    if module == ModuleName.HEALTH:
        return {
            "module": module.value,
            "version": "deterministic",
            "trained_at": datetime.now(UTC).isoformat(),
            "reloaded": True,
        }
    bundle = registry.reload(module.value)
    if bundle is None:
        raise HTTPException(
            status_code=404,
            detail=f"No artifact on disk for module '{module.value}' at {settings.model_dir}",
        )
    return {**bundle["meta"], "reloaded": True}


@app.get("/admin/scheduler", tags=["admin"], dependencies=[Depends(verify_admin_key)])
async def scheduler_status() -> dict:
    return get_scheduler_status()


@app.post("/admin/retrain", tags=["admin"], dependencies=[Depends(verify_admin_key)])
async def trigger_retraining(background_tasks: BackgroundTasks) -> dict:
    background_tasks.add_task(run_retraining_job)
    return {
        "status": "triggered",
        "message": "Model retraining job queued in background",
        "triggered_at": datetime.now(UTC).isoformat(),
    }
