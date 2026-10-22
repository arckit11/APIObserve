"""Feature engineering — Table II of the paper.

Two canonical feature sets:

1. Snapshot features (11 columns) — for point-in-time models (Anomaly Detection, Health Score):
   - Raw metrics: request_count, response_time_mean, response_time_p95, response_time_p99, error_rate
   - Resource metrics: cpu_usage_pct, memory_usage_pct
   - Cyclical time encodings: hour_sin, hour_cos, dow_sin, dow_cos (derived from timestamp)
   Calculated identically in batch via ``build_snapshot_feature_frame(df)`` and at inference via
   ``engineer_snapshot_row(snapshot)``.

2. Sequence features (15 columns) — for time-series forecasting models (Traffic, Failure):
   - All snapshot features plus lag_1h, lag_6h, lag_24h, and rolling_15m RPM.
   Calculated via ``build_feature_frame(df)`` at training time and ``engineer_row(history)`` at inference.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

# Point-in-time snapshot features (no lag requirements)
SNAPSHOT_FEATURE_COLUMNS: list[str] = [
    "request_count",
    "response_time_mean",
    "response_time_p95",
    "response_time_p99",
    "error_rate",
    "cpu_usage_pct",
    "memory_usage_pct",
    "hour_sin",
    "hour_cos",
    "dow_sin",
    "dow_cos",
]

# Full sequence features (includes 24h lags + rolling windows)
FEATURE_COLUMNS: list[str] = [
    *SNAPSHOT_FEATURE_COLUMNS,
    "rpm_lag_1h",
    "rpm_lag_6h",
    "rpm_lag_24h",
    "rpm_rolling_15m",
]


@dataclass
class FeatureSpec:
    """Static description of engineered features — for docs and validation."""

    lag_minutes: tuple[int, ...] = (60, 360, 1440)   # 1h, 6h, 24h
    rolling_window_minutes: int = 15
    cyclical: tuple[str, ...] = ("hour_of_day", "day_of_week")

    def required_history_minutes(self) -> int:
        return max(self.lag_minutes) + self.rolling_window_minutes


SPEC = FeatureSpec()


def _add_cyclical_time(df: pd.DataFrame, ts_col: str = "window_start") -> pd.DataFrame:
    ts = pd.to_datetime(df[ts_col], utc=True)
    hour = ts.dt.hour.astype(float)
    dow = ts.dt.dayofweek.astype(float)
    df["hour_sin"] = np.sin(2 * np.pi * hour / 24.0)
    df["hour_cos"] = np.cos(2 * np.pi * hour / 24.0)
    df["dow_sin"] = np.sin(2 * np.pi * dow / 7.0)
    df["dow_cos"] = np.cos(2 * np.pi * dow / 7.0)
    return df


def _ensure_resource_columns(df: pd.DataFrame) -> pd.DataFrame:
    for col in ("cpu_usage_pct", "memory_usage_pct"):
        if col not in df.columns:
            df[col] = 0.0
        else:
            df[col] = df[col].fillna(0.0)
    return df


def build_snapshot_feature_frame(
    df: pd.DataFrame,
    ts_col: str = "window_start",
) -> pd.DataFrame:
    """Canonical feature generation for snapshot-level models (Anomaly Detection).

    Extracts raw metrics, ensures resource columns, and calculates cyclical time
    encodings without requiring any historical lag rows.
    """
    if df.empty:
        return df

    out = df.copy()
    out = _ensure_resource_columns(out)
    out = _add_cyclical_time(out, ts_col=ts_col)
    return out


def engineer_snapshot_row(snapshot: Any) -> pd.DataFrame:
    """Inference-time feature generator for a single MetricSnapshot.

    Accepts a Pydantic MetricSnapshot instance or a dictionary. Returns a
    single-row DataFrame containing exactly SNAPSHOT_FEATURE_COLUMNS in canonical order.
    """
    if hasattr(snapshot, "model_dump"):
        data = snapshot.model_dump(mode="python")
    elif isinstance(snapshot, dict):
        data = dict(snapshot)
    else:
        data = {
            "request_count": getattr(snapshot, "request_count", 0),
            "response_time_mean": getattr(snapshot, "response_time_mean", 0.0),
            "response_time_p95": getattr(snapshot, "response_time_p95", 0.0),
            "response_time_p99": getattr(snapshot, "response_time_p99", 0.0),
            "error_rate": getattr(snapshot, "error_rate", 0.0),
            "cpu_usage_pct": getattr(snapshot, "cpu_usage_pct", 0.0) or 0.0,
            "memory_usage_pct": getattr(snapshot, "memory_usage_pct", 0.0) or 0.0,
            "timestamp": getattr(snapshot, "timestamp", None),
        }

    ts = pd.to_datetime(data.get("timestamp"), utc=True)
    hour = float(ts.hour)
    dow = float(ts.dayofweek)

    row = {
        "request_count": float(data.get("request_count", 0)),
        "response_time_mean": float(data.get("response_time_mean", 0.0)),
        "response_time_p95": float(data.get("response_time_p95", 0.0)),
        "response_time_p99": float(data.get("response_time_p99", 0.0)),
        "error_rate": float(data.get("error_rate", 0.0)),
        "cpu_usage_pct": float(data.get("cpu_usage_pct") or 0.0),
        "memory_usage_pct": float(data.get("memory_usage_pct") or 0.0),
        "hour_sin": np.sin(2 * np.pi * hour / 24.0),
        "hour_cos": np.cos(2 * np.pi * hour / 24.0),
        "dow_sin": np.sin(2 * np.pi * dow / 7.0),
        "dow_cos": np.cos(2 * np.pi * dow / 7.0),
    }

    return pd.DataFrame([row])[SNAPSHOT_FEATURE_COLUMNS]


def build_feature_frame(
    df: pd.DataFrame,
    group_col: str = "service_id",
    rpm_col: str = "rpm",
    ts_col: str = "window_start",
) -> pd.DataFrame:
    """Batch sequence feature engineering.

    Expects raw metric columns from the ``metrics`` table plus
    ``service_id`` and ``window_start``. Returns a new DataFrame that keeps
    the original identifiers and adds FEATURE_COLUMNS.
    """
    if df.empty:
        return df

    df = df.sort_values([group_col, ts_col]).reset_index(drop=True)
    df = _ensure_resource_columns(df)
    df = _add_cyclical_time(df, ts_col=ts_col)

    grouped = df.groupby(group_col, sort=False)[rpm_col]
    df["rpm_lag_1h"] = grouped.shift(60)
    df["rpm_lag_6h"] = grouped.shift(360)
    df["rpm_lag_24h"] = grouped.shift(1440)
    df["rpm_rolling_15m"] = grouped.transform(
        lambda s: s.rolling(15, min_periods=1).mean()
    )
    return df


def engineer_row(history: list[dict], group_col: str = "service_id") -> pd.DataFrame:
    """Inference-time feature engineering for sequence models (Traffic, Failure).

    ``history`` is a list of MetricSnapshot-shaped dicts (chronologically
    ordered). Returns the latest observation with all 15 FEATURE_COLUMNS.
    """
    required = SPEC.required_history_minutes()
    if len(history) < required:
        raise ValueError(
            f"Insufficient history: sequence models require at least {required} "
            f"minutes of observation history, but received {len(history)}"
        )

    df = pd.DataFrame(history)
    if "rpm" not in df.columns:
        df["rpm"] = df["request_count"].astype(float)
    if "window_start" not in df.columns:
        df["window_start"] = df["timestamp"]
    if group_col not in df.columns:
        df[group_col] = df.get("service_id", "unknown")

    engineered = build_feature_frame(df, group_col=group_col)
    latest = engineered.iloc[[-1]][FEATURE_COLUMNS].copy()
    if latest.isna().any().any():
        raise ValueError(
            "engineer_row produced NaN feature values; check for missing time windows"
        )
    return latest
