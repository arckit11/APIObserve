"""Runtime configuration loaded from env vars via pydantic-settings.

Values documented in the root ``.env.example`` map 1:1 into this class.
"""
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_model_dir() -> Path:
    container_path = Path("/app/models")
    if container_path.exists():
        return container_path
    local_path = Path(__file__).resolve().parent / "models"
    return local_path


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Service
    service_name: str = "ml-service"
    port: int = Field(8000, alias="PORT_ML")
    admin_api_key: str = Field("dev_admin_key_secret", alias="ML_ADMIN_API_KEY")

    # Model artifacts on disk (joblib)
    model_dir: Path = Field(default_factory=_default_model_dir, alias="ML_MODEL_DIR")

    @field_validator("model_dir", mode="after")
    @classmethod
    def resolve_model_dir(cls, v: Path) -> Path:
        if str(v) == "/app/models" and not v.exists():
            return Path(__file__).resolve().parent / "models"
        return v

    # Analytics Service (for training data retrieval + inference features)
    analytics_base_url: str = Field(
        "http://analytics-service:8084", alias="ML_ANALYTICS_BASE_URL"
    )

    # Kafka
    kafka_bootstrap: str = Field("kafka:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    topic_predictions: str = Field("predictions", alias="KAFKA_TOPIC_PREDICTIONS")
    topic_service_health: str = Field(
        "service-health", alias="KAFKA_TOPIC_SERVICE_HEALTH"
    )

    # Retraining
    training_cron: str = Field("0 3 * * *", alias="ML_TRAINING_SCHEDULE_CRON")

    # Direct-Postgres reads during Phase 3. Analytics HTTP endpoint replaces
    # these in Phase 4.
    pg_host: str = Field("localhost", alias="POSTGRES_HOST")
    pg_port: int = Field(5432, alias="POSTGRES_PORT")
    pg_db: str = Field("api_analytics", alias="POSTGRES_DB")
    pg_user: str = Field("api_analytics", alias="POSTGRES_USER")
    pg_password: str = Field("change_me_locally", alias="POSTGRES_PASSWORD")

    @property
    def pg_dsn(self) -> str:
        return (
            f"host={self.pg_host} port={self.pg_port} "
            f"dbname={self.pg_db} user={self.pg_user} password={self.pg_password}"
        )


settings = Settings()
