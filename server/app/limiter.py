from slowapi import Limiter
from slowapi.util import get_remote_address

from app.config import settings


def _key(request) -> str:
    """Rate-limit key.

    Tote is tailnet-only and sits behind no proxy by default, so the peer address IS the
    client. `trust_proxy` exists for the day that stops being true — reading a forwarded header
    without a trusted proxy in front lets any caller forge its own rate-limit bucket.
    """
    if settings.trust_proxy:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return get_remote_address(request)


limiter = Limiter(key_func=_key)
