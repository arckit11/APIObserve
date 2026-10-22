"""Train the Anomaly Detection module.

Isolation Forest on snapshot features (request metrics, resources, cyclical time).
Trained on chronological training split, then evaluated against a separate holdout
test period reporting precision, recall, F1, and false-positive rate against ground-truth labels.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.metrics import confusion_matrix, f1_score, precision_score, recall_score
from sklearn.preprocessing import StandardScaler

from app.features import SNAPSHOT_FEATURE_COLUMNS, build_snapshot_feature_frame
from app.training import registry

CONTAMINATION_GRID = (0.01, 0.02, 0.03, 0.05, 0.07, 0.10)


def _load_labels() -> pd.DataFrame | None:
    """Ground-truth anomaly labels from the synthetic-data generator.

    Written to ``synthetic-data/out/labels.csv`` when the generator runs.
    Returns None if unavailable — anomaly detection remains unsupervised.
    """
    candidates = [
        Path("synthetic-data/out/labels.csv"),
        Path("../synthetic-data/out/labels.csv"),
    ]
    for p in candidates:
        if p.exists():
            df = pd.read_csv(p, parse_dates=["window_start"])
            df["window_start"] = pd.to_datetime(df["window_start"], utc=True)
            df["service_id"] = df["service_id"].astype(str)
            return df
    return None


def _chronological_split(df: pd.DataFrame, test_frac: float = 0.2) -> tuple[pd.DataFrame, pd.DataFrame]:
    df = df.sort_values("window_start").reset_index(drop=True)
    cut = int(len(df) * (1.0 - test_frac))
    return df.iloc[:cut], df.iloc[cut:]


def train(df: pd.DataFrame, model_dir: Path) -> dict:
    engineered = build_snapshot_feature_frame(df)
    engineered = engineered.dropna(subset=SNAPSHOT_FEATURE_COLUMNS).reset_index(drop=True)

    labels_df = _load_labels()
    if labels_df is not None:
        engineered = engineered.merge(
            labels_df[["service_id", "window_start", "is_anomaly"]],
            on=["service_id", "window_start"],
            how="left",
        )
        engineered["is_anomaly"] = engineered["is_anomaly"].fillna(False).astype(int)
    else:
        engineered["is_anomaly"] = 0

    train_df, test_df = _chronological_split(engineered, test_frac=0.2)

    X_train = train_df[SNAPSHOT_FEATURE_COLUMNS].values
    X_test = test_df[SNAPSHOT_FEATURE_COLUMNS].values
    y_train = train_df["is_anomaly"].values
    y_test = test_df["is_anomaly"].values

    scaler = StandardScaler()
    X_train_s = scaler.fit_transform(X_train)
    X_test_s = scaler.transform(X_test)

    hyperparams_base = dict(
        n_estimators=200,
        max_samples="auto",
        n_jobs=-1,
        random_state=42,
    )

    best_contamination = 0.05
    best_f1 = -1.0

    if y_train.sum() > 0:
        for c in CONTAMINATION_GRID:
            m = IsolationForest(contamination=c, **hyperparams_base)
            m.fit(X_train_s)
            preds = (m.predict(X_train_s) == -1).astype(int)
            f1 = f1_score(y_train, preds, zero_division=0)
            if f1 > best_f1:
                best_f1 = f1
                best_contamination = c

    hyperparams = {**hyperparams_base, "contamination": best_contamination}
    model = IsolationForest(**hyperparams)
    model.fit(X_train_s)

    metrics: dict[str, float] = {
        "contamination": best_contamination,
        "n_train_rows": float(len(train_df)),
        "n_test_rows": float(len(test_df)),
    }

    # Evaluate on separate chronological holdout period
    if y_test.sum() > 0:
        test_preds = (model.predict(X_test_s) == -1).astype(int)
        prec = float(precision_score(y_test, test_preds, zero_division=0))
        rec = float(recall_score(y_test, test_preds, zero_division=0))
        f1 = float(f1_score(y_test, test_preds, zero_division=0))

        tn, fp, fn, tp = confusion_matrix(y_test, test_preds, labels=[0, 1]).ravel()
        fpr = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0

        metrics["holdout_precision"] = prec
        metrics["holdout_recall"] = rec
        metrics["holdout_f1"] = f1
        metrics["holdout_false_positive_rate"] = fpr
        metrics["n_injected_holdout"] = float(int(y_test.sum()))
    else:
        metrics["n_injected_holdout"] = 0.0

    meta = registry.save(
        module="anomaly",
        model=model,
        scaler=scaler,
        feature_cols=SNAPSHOT_FEATURE_COLUMNS,
        metrics=metrics,
        hyperparams=hyperparams,
        training_rows=len(train_df),
        algorithm="sklearn.ensemble.IsolationForest",
        model_dir=model_dir,
    )
    return {
        "model_version": meta["version"],
        "training_rows": meta["training_rows"],
        "metrics": metrics,
    }
