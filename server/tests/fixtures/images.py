"""Real images, built with Pillow, no binaries in git.

Crate's entire photo pipeline was green for weeks while every test monkeypatched `clean_photo`
and uploaded fake PNG bytes — so no pixel had ever been decoded, and the tests were hiding a
defect that turned every dark garment pure black. These fixtures exist so that cannot happen
here: the bytes that go into a test are bytes Pillow will actually open.
"""

import io

from PIL import Image, ImageDraw


def photo_bytes(
    *,
    size: tuple[int, int] = (400, 300),
    subject: tuple[int, int, int] = (40, 90, 160),
    background: tuple[int, int, int] = (200, 200, 205),
    fmt: str = "JPEG",
) -> bytes:
    """A photo-shaped image: a distinct subject on a duller ground.

    Not a flat fill. A single-colour image has no histogram to correct and no subject to
    segment, so it would exercise the cleanup path without testing anything it does.
    """
    img = Image.new("RGB", size, background)
    draw = ImageDraw.Draw(img)
    w, h = size
    draw.rectangle([w // 4, h // 4, 3 * w // 4, 3 * h // 4], fill=subject)
    # A little internal detail so crop-to-subject has a real bounding box to find.
    draw.ellipse([w // 3, h // 3, w // 2, h // 2], fill=(230, 230, 240))

    buf = io.BytesIO()
    img.save(buf, format=fmt, quality=90) if fmt == "JPEG" else img.save(buf, format=fmt)
    return buf.getvalue()


def dark_photo_bytes() -> bytes:
    """A dark subject on a mid ground.

    This is the shape of the image that exposed Crate's blackening bug: applying levels AFTER
    compositing onto white makes the subject the darkest content in the frame, so the shadow clip
    lands on the subject and maps it toward black. Kept as a named fixture so the regression has
    an obvious home.
    """
    return photo_bytes(subject=(28, 30, 38), background=(150, 150, 155))


def open_image(data: bytes) -> Image.Image:
    return Image.open(io.BytesIO(data))


def mean_of_center(data: bytes) -> tuple[int, int, int]:
    """Average RGB of the middle of an image — i.e. of the subject.

    Used to assert the subject survived cleanup, which is the property a byte-count or
    "it didn't raise" check cannot see.
    """
    img = open_image(data).convert("RGB")
    w, h = img.size
    box = img.crop((int(w * 0.4), int(h * 0.4), int(w * 0.6), int(h * 0.6)))
    pixels = list(box.getdata())
    n = len(pixels)
    return (
        sum(p[0] for p in pixels) // n,
        sum(p[1] for p in pixels) // n,
        sum(p[2] for p in pixels) // n,
    )


def pure_black_fraction(data: bytes) -> float:
    """Share of pixels that are exactly (0, 0, 0).

    The direct measure of the blackening defect. A levels pass applied in the wrong order
    produced images that were almost entirely this.
    """
    img = open_image(data).convert("RGB")
    pixels = list(img.getdata())
    return sum(1 for p in pixels if p == (0, 0, 0)) / len(pixels)
