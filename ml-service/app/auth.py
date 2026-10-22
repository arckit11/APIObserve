"""Authentication dependencies for ML Service internal endpoints."""
from fastapi import Header, HTTPException, status

from app.config import settings


async def verify_admin_key(
    x_admin_key: str | None = Header(None, alias="X-Admin-Key")
) -> None:
    """Validate X-Admin-Key against ML_ADMIN_API_KEY.

    Protects admin endpoints (/admin/*) from unauthorized direct access.
    """
    if not x_admin_key or x_admin_key != settings.admin_api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing X-Admin-Key header",
            headers={"WWW-Authenticate": "ApiKey"},
        )
