"""Photo cleanup: levels → background removal → crop-to-subject, kept TRANSPARENT.

All local CPU (rembg U2-Net + Pillow), no cloud. The U2-Net weights are baked into the
Docker image (see Dockerfile) so the container works offline. Degrades honestly: rembg
unavailable or removal fails ⇒ Pillow-only pass (levels, no background swap) — the
pipeline never blocks a draft on cleanup.

Pure-ish and synchronous by design (CPU-bound); the scan pipeline runs it in a thread.
"""

import io
import logging

from PIL import Image, ImageOps

from app.config import settings

logger = logging.getLogger(__name__)

# Margin kept around the subject when cropping to its alpha bounding box, as a fraction of
# the box's long edge — tight enough to fill the frame, loose enough not to amputate shadows.
_CROP_MARGIN = 0.06
# The client already downscales to <=1600px; this is the backstop for a gallery pick
# that bypassed it.
_MAX_EDGE = 1600


def _remove_background(image_bytes: bytes) -> Image.Image | None:
    """rembg → RGBA cutout, or None when disabled/unavailable/failed."""
    if not settings.background_removal_enabled:
        return None
    try:
        from rembg import remove  # heavy import (onnxruntime) — deliberately lazy
    except Exception:  # noqa: BLE001 - see below; pragma: no cover (envs without rembg)
        # Deliberately blind: this import fails in more ways than ImportError (a broken
        # onnxruntime build, a missing native lib, a CUDA stub). Any of them must degrade
        # to the Pillow-only path, never 500 a scan.
        logger.warning("rembg unavailable; falling back to Pillow-only cleanup")
        return None
    try:
        result = remove(image_bytes)
        return Image.open(io.BytesIO(result)).convert("RGBA")
    except Exception:
        logger.exception("background removal failed; falling back to Pillow-only cleanup")
        return None


def _crop_to_subject(cutout: Image.Image) -> Image.Image:
    alpha = cutout.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:  # fully transparent — treat as removal failure upstream
        return cutout
    left, top, right, bottom = bbox
    margin = int(max(right - left, bottom - top) * _CROP_MARGIN)
    left = max(0, left - margin)
    top = max(0, top - margin)
    right = min(cutout.width, right + margin)
    bottom = min(cutout.height, bottom + margin)
    return cutout.crop((left, top, right, bottom))


def _shrink(image: Image.Image) -> Image.Image:
    if max(image.size) <= _MAX_EDGE:
        return image
    image.thumbnail((_MAX_EDGE, _MAX_EDGE), Image.LANCZOS)
    return image


def _levels(image: Image.Image) -> Image.Image:
    """Gentle levels: clip 1% shadows/highlights — brightens typical indoor phone shots
    without the lying-about-condition look of aggressive filters.

    `preserve_tone=True` derives ONE mapping from luminance instead of stretching each RGB
    channel against its own endpoints. Per-channel is what "auto levels" usually means, but
    it is wrong here: a saturated garment on a neutral ground drives each channel's endpoints
    differently, so the correction pushes a complementary cast into the background (a red
    shirt turned the backdrop teal) and shifts the garment's own hue — and colour is one of the few
    things a photo can tell you about a boxed item that the catalog cannot.
    """
    return ImageOps.autocontrast(image, cutoff=1, preserve_tone=True)


def clean_photo(image_bytes: bytes) -> bytes:
    """Original bytes → cleaned PNG bytes. Never raises on bad model output — worst case
    is a levels-only pass of the original.

    Order matters: levels are applied to the ORIGINAL photo, before the background is
    removed. Applying them afterwards reads the histogram of a cut-out subject sitting on a
    synthetic ground, in which the garment is by definition the darkest content — so the 1%
    clip lands on the garment itself and maps it toward black. On a flat, evenly lit tee that
    was total: every colourway, including a light heather grey, came out pure black.
    Correcting the capture's exposure first leaves the garment where the camera saw it.

    The result keeps its alpha. Compositing onto white was inherited from Crate, where an
    eBay listing wants exactly that; a household catalog read in dark mode does not.
    """
    corrected = _levels(Image.open(io.BytesIO(image_bytes)).convert("RGB"))

    # rembg segments the exposure-corrected photo; re-encoded losslessly so the model sees
    # exactly what we will composite from.
    corrected_bytes = io.BytesIO()
    corrected.save(corrected_bytes, format="PNG")
    cutout = _remove_background(corrected_bytes.getvalue())

    if cutout is not None and cutout.getchannel("A").getbbox() is not None:
        # Kept as an RGBA cutout — NOT composited onto white.
        #
        # White is the eBay convention Crate needs and it was inherited wholesale. It is wrong
        # here: Tote's catalog is read on a phone that is usually in dark mode, where every
        # photograph became a glaring white card in a charcoal list. Transparency lets each
        # photo sit on whatever surface it is on and look right in both themes, which is the
        # same reasoning as the accent's light/dark role swap.
        #
        # Safe for the model too, because the model never sees this file: `scan_pipeline`
        # sends the ORIGINALS to both the identify and the label pass, measured in Crate as
        # the better input. Worth knowing before anyone points a vision call at a cleaned
        # copy — most stacks flatten alpha to BLACK, which would put a dark subject on a
        # dark ground and be strictly worse than the white this used to store.
        cleaned = _crop_to_subject(cutout)
    else:
        cleaned = corrected

    cleaned = _shrink(cleaned)

    out = io.BytesIO()
    cleaned.save(out, format="PNG")
    return out.getvalue()
