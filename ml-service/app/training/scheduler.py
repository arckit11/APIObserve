"""APScheduler-based scheduled retraining mechanism.

Reads settings.training_cron (default: '0 3 * * *'), runs periodic model retraining,
and automatically reloads updated artifacts into the runtime registry.
"""
from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger

from app.clients.metrics import load_metrics
from app.config import settings
from app.training import registry
from app.training.cli import TRAINERS

logger = logging.getLogger(__name__)

_scheduler: AsyncIOScheduler | None = None
_last_run_info: dict[str, Any] = {
    "last_run_at": None,
    "last_status": "never_run",
    "results": [],
}


def run_retraining_sync(days: int = 30, modules: list[str] | None = None) -> list[dict]:
    """Execute training for specified modules and reload them in registry."""
    global _last_run_info
    target = modules or list(TRAINERS.keys())
    out_dir = settings.model_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    end = datetime.now(UTC).replace(second=0, microsecond=0)
    start = end - timedelta(days=days)

    logger.info("Starting scheduled retraining for %s from %s to %s", target, start, end)
    try:
        df = load_metrics(settings.pg_dsn, window_size="1m", since=start, until=end)
    except Exception as e:
        logger.error("Failed to load metrics from DB for retraining: %s", e)
        _last_run_info = {
            "last_run_at": datetime.now(UTC).isoformat(),
            "last_status": "failed_db_error",
            "results": [],
            "error": str(e),
        }
        return []

    if df.empty:
        logger.warning("No metrics found in DB; skipping retraining")
        _last_run_info = {
            "last_run_at": datetime.now(UTC).isoformat(),
            "last_status": "skipped_no_data",
            "results": [],
        }
        return []

    results = []
    for name in target:
        if name not in TRAINERS:
            continue
        try:
            logger.info("Training module %s...", name)
            res = TRAINERS[name](df, out_dir)
            registry.reload(name)
            results.append({"module": name, "status": "success", **res})
        except Exception as e:
            logger.error("Failed to train module %s: %s", name, e, exc_info=True)
            results.append({"module": name, "status": "failed", "error": str(e)})

    _last_run_info = {
        "last_run_at": datetime.now(UTC).isoformat(),
        "last_status": "completed",
        "results": results,
    }
    return results


async def run_retraining_job() -> None:
    import asyncio
    await asyncio.to_thread(run_retraining_sync)


def start_scheduler() -> AsyncIOScheduler:
    global _scheduler
    if _scheduler is not None and _scheduler.running:
        return _scheduler

    _scheduler = AsyncIOScheduler(timezone="UTC")
    try:
        trigger = CronTrigger.from_crontab(settings.training_cron)
        _scheduler.add_job(
            run_retraining_job,
            trigger=trigger,
            id="ml_scheduled_retraining",
            name="Scheduled ML Retraining",
            replace_existing=True,
        )
        _scheduler.start()
        logger.info("Scheduled ML retraining started with cron '%s'", settings.training_cron)
    except Exception as e:
        logger.error("Failed to initialize retraining scheduler: %s", e, exc_info=True)

    return _scheduler


def shutdown_scheduler() -> None:
    global _scheduler
    if _scheduler is not None and _scheduler.running:
        _scheduler.shutdown(wait=False)
        logger.info("Scheduled ML retraining shut down")
        _scheduler = None


def get_scheduler_status() -> dict[str, Any]:
    job = _scheduler.get_job("ml_scheduled_retraining") if _scheduler else None
    next_run = job.next_run_time.isoformat() if job and job.next_run_time else None
    return {
        "running": bool(_scheduler and _scheduler.running),
        "cron": settings.training_cron,
        "next_run_at": next_run,
        "last_run": _last_run_info,
    }
