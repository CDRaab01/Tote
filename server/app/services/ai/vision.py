"""LM Studio vision client.

A local OpenAI-compatible chat-completions call. Transport failures are mapped to clean HTTP
statuses so the caller can tell "not configured / unreachable" apart from "the photo was
unreadable" — those need completely different responses, and collapsing them is how a dead
container gets recorded as an unidentifiable item.
"""

import base64

import httpx
from fastapi import HTTPException, status

from app.config import settings
from app.services.ai.identify_prompts import (
    IdentifyDraft,
    build_identify_messages,
    parse_identify,
)
from app.services.ai.label_prompts import (
    LabelDraft,
    build_label_messages,
    parse_label,
)


def data_url(image_bytes: bytes, content_type: str) -> str:
    encoded = base64.b64encode(image_bytes).decode("ascii")
    return f"data:{content_type};base64,{encoded}"


async def _chat_vision(messages: list[dict], client: httpx.AsyncClient | None) -> str:
    """One multimodal round trip, returning the raw model text.

    **No `max_tokens`, deliberately.** The suite's pinned model (`google/gemma-4-e4b`) is a
    reasoning model: it emits hidden reasoning tokens that share the same budget and produces NO
    content until it is done thinking. An answer-sized cap therefore returns an empty string,
    which every parser downstream reads as "unreadable photo" — a silent, total failure that
    looks like a model limitation rather than a config mistake. Leaving it unset is what keeps
    that from happening, and it is the reason this comment exists.

    `client` is the injection seam for tests (httpx.MockTransport); production always uses the
    real network client.
    """
    owns_client = client is None
    active = client or httpx.AsyncClient(timeout=settings.lm_studio_timeout)

    try:
        try:
            response = await active.post(
                f"{settings.lm_studio_base_url}/chat/completions",
                json={
                    "model": settings.lm_studio_vision_model,
                    "messages": messages,
                    # Low, not zero: faithfulness over invention, without the degenerate
                    # repetition greedy decoding sometimes falls into on vision inputs.
                    "temperature": 0.2,
                },
            )
            response.raise_for_status()
        finally:
            if owns_client:
                await active.aclose()
    except httpx.TimeoutException as e:
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT,
            detail="LM Studio timed out — the vision model may still be loading.",
        ) from e
    except httpx.HTTPStatusError as e:
        # Includes a wrong LM_STUDIO_VISION_MODEL: LM Studio 404s an unknown model, so this
        # branch is what catches a bad model pin rather than a bad URL.
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LM Studio rejected the request.",
        ) from e
    except httpx.RequestError as e:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Couldn't reach LM Studio. Is it running?",
        ) from e

    body = response.json()
    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as e:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LM Studio returned a malformed response.",
        ) from e
    if not isinstance(content, str):
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LM Studio returned a malformed response.",
        )
    # Note: gemma-4 also returns `reasoning_content` alongside `content`. Reading only `content`
    # is correct — the reasoning is not an answer — and is what the measured Crate run confirmed.
    return content


async def identify_item(
    image_data_urls: list[str],
    categories: list[str] | None = None,
    client: httpx.AsyncClient | None = None,
) -> IdentifyDraft:
    """Identify one item from 1-N photos.

    Content failures degrade to a low-confidence empty draft — an unreadable photo still produces
    a row a human can fill in. Transport failures RAISE, so the pipeline can record that the
    model was unreachable rather than pretending the photo was bad.
    """
    messages = build_identify_messages(image_data_urls, categories)
    raw_text = await _chat_vision(messages, client)
    draft = parse_identify(raw_text, categories)
    if draft is None:
        return IdentifyDraft(confidence="low")
    return draft


async def read_label(
    image_data_urls: list[str],
    client: httpx.AsyncClient | None = None,
) -> LabelDraft | None:
    """Read size/department/material off a care-label photo, or None if nothing was legible.

    Lives here rather than beside its prompt because `_chat_vision` is module-private, and
    reaching across modules for a `_`-prefixed name would be a new precedent; keeping it here
    means the transport, the timeout and the 503/504/502 mapping have exactly one implementation.

    Transport failures RAISE, like `identify_item`'s. The caller decides whether that is fatal,
    and in the scan pipeline it deliberately is not — a label call that 503s must never be able to
    rewrite a good identification as `identify_unavailable`.
    """
    messages = build_label_messages(image_data_urls)
    raw_text = await _chat_vision(messages, client)
    return parse_label(raw_text)
