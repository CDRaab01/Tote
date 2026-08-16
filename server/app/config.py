from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """App configuration from environment variables (and server/.env locally).

    Suite rule: required non-secret config is pinned in docker-compose.yml's `environment:`
    block (Compose does not re-read a changed env_file on recreate — this has silently dropped
    config in production three times across the suite); secrets live in server/.env only. A
    `None` credential/URL disables its feature (404/503) rather than crashing — every
    integration must degrade honestly.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    secret_key: str
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30
    refresh_token_expire_days: int = 7

    # Test-suite setting: pooled asyncpg connections bind to the creating event loop, which
    # breaks under pytest-asyncio's per-test loops ("Task attached to a different loop").
    # See app.database.
    db_nullpool: bool = False

    # Deploy hardening. Tote is tailnet-only, so TRUST_PROXY stays off unless a proxy fronts it.
    trust_proxy: bool = False
    hsts_enabled: bool = False
    docs_enabled: bool = True

    # Deploy stamp surfaced by GET /version (exported by deploy/redeploy.* before compose up).
    git_sha: str = "unknown"
    built_at: str = "unknown"

    # Suite SSO (Magpie/Crate SSO-only pattern): unset => POST /auth/suite is disabled (404).
    # Tote has no password auth at all, so without these the app has no login path — the two
    # vars are pinned in compose `environment:` in production. Arrives in Phase 1.
    suite_jwks_url: str | None = None
    suite_issuer: str | None = None
    suite_audience: str = "suite"

    external_timeout_seconds: float = 8.0

    # The household's timezone, for date-only questions like "is this loan overdue".
    #
    # This is NOT cosmetic. The container runs UTC and the house is US Eastern, so comparing
    # `expected_back` against a UTC date marks an item overdue at 7pm local on the day it is
    # actually still due. Pinned as a literal in compose `environment:`; an unknown zone falls
    # back to UTC rather than crashing, because a slightly-early nudge beats a dead endpoint.
    local_timezone: str = "UTC"

    # --- Photo capture (Phase 4) -----------------------------------------------------------
    # Binaries on a volume, paths in the DB. The 8 MB cap matches the client's <=1600px JPEG
    # downscale contract; anything larger means the client skipped it, and a 413 says so rather
    # than letting one gallery pick fill the volume.
    photos_dir: str = "/data/photos"
    photo_max_bytes: int = 8 * 1024 * 1024

    # rembg background removal (local U2-Net, CPU). Disabled => Pillow-only cleanup. The pipeline
    # DEGRADES rather than failing: a draft is never blocked on cleanup, because the photo has
    # already been saved by then and it is the part that cannot be recreated.
    background_removal_enabled: bool = True

    # LM Studio vision. Both are pinned in compose `environment:` in production — the default
    # below is correct only for bare-metal local dev, because inside the container `localhost` is
    # the container, so a scan would fail with a connect-refused 503 while LM Studio ran happily
    # on the host.
    lm_studio_base_url: str = "http://localhost:1234/v1"
    lm_studio_vision_model: str = "google/gemma-4-e4b"
    # Generous on purpose: the pinned model is a reasoning model and spends real time thinking
    # before it emits anything. Measured at ~15 s per item in Crate, so 60 s is headroom rather
    # than optimism.
    lm_studio_timeout: float = 60.0

    # The base the NFC tag's URI record is written against. Read from settings rather than a
    # constant because a written tag is a PHYSICAL object in an attic — it cannot be patched by
    # a deploy, so the value that gets baked into tags must be changeable without a code change.
    # Arrives in Phase 3.
    nfc_uri_base: str = "https://dragonfly.tail2ce561.ts.net:8448"


settings = Settings()
