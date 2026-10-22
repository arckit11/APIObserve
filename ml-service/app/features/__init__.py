"""Feature engineering pipeline.

Turns raw per-minute metric snapshots into the feature matrix used across
all XGBoost / Random Forest / Isolation Forest models.
"""

from app.features.pipeline import (
    FEATURE_COLUMNS,
    SNAPSHOT_FEATURE_COLUMNS,
    SPEC,
    build_feature_frame,
    build_snapshot_feature_frame,
    engineer_row,
    engineer_snapshot_row,
)

__all__ = [
    "FEATURE_COLUMNS",
    "SNAPSHOT_FEATURE_COLUMNS",
    "SPEC",
    "build_feature_frame",
    "build_snapshot_feature_frame",
    "engineer_row",
    "engineer_snapshot_row",
]
