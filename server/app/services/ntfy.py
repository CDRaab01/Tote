"""Push notifications via the household's **self-hosted** ntfy. Fail-soft, never fatal.

Two rules that are not preferences:

* **Never ntfy.sh.** A topic there is effectively a public URL — anyone who guesses or sees it
  reads every message. This app's notifications name what you own and who has it, which is the
  same reasoning that keeps the whole service tailnet-only. `NTFY_BASE_URL` is validated against
  that here rather than trusted, because the check costs nothing and the mistake is silent.
* **A failed notification never fails the request that triggered it.** A nudge is a courtesy; a
  down ntfy must not turn "your loans are overdue" into a 500. Failures are logged, because a
  notification channel that is quietly broken is indistinguishable from one with nothing to say.

Configuration is compose `environment:` **literals**, not `env_file` — Compose does not re-read
an env_file on recreate, and an interpolated `${NTFY_TOPIC:-}` reading from a root `.env` this
repo does not have is exactly how Crate's notifications were silently off for weeks.
"""

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

# Hosts that are public message boards regardless of how obscure the topic is.
_PUBLIC_HOSTS = ("ntfy.sh", "www.ntfy.sh")


def is_configured() -> bool:
    return bool(settings.ntfy_base_url and settings.ntfy_topic)


def _rejected_reason() -> str | None:
    """Why this configuration must not be used, or None if it is fine."""
    base = (settings.ntfy_base_url or "").lower()
    if any(host in base for host in _PUBLIC_HOSTS):
        return (
            "NTFY_BASE_URL points at ntfy.sh, whose topics are effectively public URLs. "
            "Tote's notifications name what you own and who has it — point this at the "
            "self-hosted ntfy instead."
        )
    return None


async def send(
    title: str,
    message: str,
    *,
    topic: str | None = None,
    priority: int = 3,
    tags: list[str] | None = None,
    client: httpx.AsyncClient | None = None,
) -> bool:
    """Send one notification. Returns whether it went out; never raises."""
    if not is_configured():
        logger.debug("ntfy not configured; skipping %r", title)
        return False

    rejected = _rejected_reason()
    if rejected:
        # A loud log rather than a silent skip: this is a misconfiguration that leaks, and the
        # only place anyone would find out is here.
        logger.error("refusing to send via ntfy: %s", rejected)
        return False

    url = f"{settings.ntfy_base_url.rstrip('/')}/{topic or settings.ntfy_topic}"
    headers = {"Title": title, "Priority": str(priority)}
    if tags:
        headers["Tags"] = ",".join(tags)

    try:
        active = client or httpx.AsyncClient(timeout=settings.external_timeout_seconds)
        try:
            response = await active.post(url, content=message.encode("utf-8"), headers=headers)
            response.raise_for_status()
        finally:
            if client is None:
                await active.aclose()
    except Exception as e:  # noqa: BLE001 - a courtesy must never fail its caller
        logger.warning("ntfy send failed (%s): %s", url, e)
        return False
    return True


def overdue_message(items: list) -> tuple[str, str]:
    """Compose the nudge. Names the things, because a count alone tells you nothing actionable.

    Capped at a handful of names: a push notification that has to be scrolled is one nobody
    reads, and the app is one tap away for the full list.
    """
    count = len(items)
    title = "1 item is overdue" if count == 1 else f"{count} items are overdue"
    shown = items[:5]
    lines = []
    for item in shown:
        who = getattr(item, "loaned_to", None)
        due = getattr(item, "expected_back", None)
        parts = [item.name]
        if who:
            parts.append(f"with {who}")
        if due:
            parts.append(f"due {due}")
        lines.append(" · ".join(parts))
    if count > len(shown):
        lines.append(f"…and {count - len(shown)} more")
    return title, "\n".join(lines)
