"""Location photos: one picture of the place itself, replacing on re-upload.

The disk assertions here matter as much as the API ones. Item photographs earned the rule the
hard way (#20): rows cascade, files do not, and every deleted row used to leak its bytes onto
the volume forever. These tests hold the location photo to that standard from day one.
"""

from pathlib import Path

from app.config import settings
from tests.fixtures.images import photo_bytes


async def _loc(c, name="Attic", **kw):
    r = await c.post("/locations", json={"name": name, **kw})
    assert r.status_code == 201, r.text
    return r.json()


def _files(data: bytes, content_type: str = "image/jpeg", name: str = "spot.jpg"):
    return {"photo": (name, data, content_type)}


def _locations_dir() -> Path:
    return Path(settings.photos_dir) / "locations"


async def test_uploading_a_jpeg_attaches_the_photo(auth_client):
    loc = await _loc(auth_client)
    r = await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(photo_bytes()))
    assert r.status_code == 200, r.text
    assert r.json()["has_photo"] is True
    # The filename is server-derived from the location's id — never the upload's own name.
    assert (_locations_dir() / f"{loc['id']}.jpg").exists()


async def test_only_jpeg_and_png_are_accepted(auth_client):
    """Narrower than the item pipeline's set on purpose: no cleanup runs behind this and the
    bytes are served back exactly as uploaded, so the accepted set is what the phone's camera
    and gallery actually produce."""
    loc = await _loc(auth_client)
    r = await auth_client.post(
        f"/locations/{loc['id']}/photo", files=_files(b"GIF89a...", "image/gif", "x.gif")
    )
    assert r.status_code == 422
    r = await auth_client.post(
        f"/locations/{loc['id']}/photo",
        files=_files(photo_bytes(fmt="WEBP"), "image/webp", "x.webp"),
    )
    assert r.status_code == 422


async def test_an_oversized_photo_is_413_like_the_scan_path(auth_client):
    loc = await _loc(auth_client)
    r = await auth_client.post(
        f"/locations/{loc['id']}/photo", files=_files(b"x" * (settings.photo_max_bytes + 1))
    )
    assert r.status_code == 413


async def test_the_photo_round_trips_byte_for_byte(auth_client):
    data = photo_bytes()
    loc = await _loc(auth_client, "Garage rack B")
    await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(data))

    r = await auth_client.get(f"/locations/{loc['id']}/photo")
    assert r.status_code == 200
    assert r.content == data
    assert r.headers["content-type"] == "image/jpeg"
    # Same caching contract as item photographs: the requester's own cache only, never a shared
    # one — this is a photograph of the inside of a house.
    assert r.headers["cache-control"] == "private, max-age=86400"


async def test_replacing_the_photo_removes_the_old_file_from_disk(auth_client):
    """The extension can change between uploads. Leaving the old file behind would strand an
    orphan on the volume while the row points at the new one."""
    loc = await _loc(auth_client)
    await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(photo_bytes()))
    assert (_locations_dir() / f"{loc['id']}.jpg").exists()

    png = photo_bytes(fmt="PNG")
    r = await auth_client.post(
        f"/locations/{loc['id']}/photo", files=_files(png, "image/png", "spot.png")
    )
    assert r.status_code == 200
    assert not (_locations_dir() / f"{loc['id']}.jpg").exists()
    assert (_locations_dir() / f"{loc['id']}.png").exists()
    assert (await auth_client.get(f"/locations/{loc['id']}/photo")).content == png


async def test_deleting_the_photo_clears_it_and_further_requests_404(auth_client):
    loc = await _loc(auth_client)
    await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(photo_bytes()))

    assert (await auth_client.delete(f"/locations/{loc['id']}/photo")).status_code == 204
    assert not (_locations_dir() / f"{loc['id']}.jpg").exists()
    assert (await auth_client.get(f"/locations/{loc['id']}/photo")).status_code == 404
    # Gone means gone: a second delete has nothing to clear.
    assert (await auth_client.delete(f"/locations/{loc['id']}/photo")).status_code == 404
    assert [row["has_photo"] for row in (await auth_client.get("/locations")).json()] == [False]


async def test_a_file_missing_on_disk_is_a_404_not_a_500(auth_client):
    """The DB path is trusted nowhere: a file that vanished on its own (a restore without the
    photos volume, a hand-cleaned disk) is a missing photo, not a server fault."""
    loc = await _loc(auth_client)
    await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(photo_bytes()))
    (_locations_dir() / f"{loc['id']}.jpg").unlink()
    assert (await auth_client.get(f"/locations/{loc['id']}/photo")).status_code == 404


async def test_deleting_a_location_removes_its_photo_file_too(auth_client):
    """The row goes with the location either way; the FILE only goes because the handler deletes
    it — the same lesson item delete learned after leaking photographs onto the volume."""
    loc = await _loc(auth_client, "Basement closet")
    await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(photo_bytes()))
    path = _locations_dir() / f"{loc['id']}.jpg"
    assert path.exists()

    assert (await auth_client.delete(f"/locations/{loc['id']}")).status_code == 204
    assert not path.exists()


async def test_another_households_location_photo_is_404_not_403(auth_client, other_client):
    """404 not 403 on every verb, so an authenticated user cannot probe which ids exist."""
    loc = await _loc(auth_client)
    await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(photo_bytes()))

    assert (await other_client.get(f"/locations/{loc['id']}/photo")).status_code == 404
    r = await other_client.post(f"/locations/{loc['id']}/photo", files=_files(photo_bytes()))
    assert r.status_code == 404
    assert (await other_client.delete(f"/locations/{loc['id']}/photo")).status_code == 404
    # And nothing above touched the real photo.
    assert (await auth_client.get(f"/locations/{loc['id']}/photo")).status_code == 200


async def test_has_photo_reports_the_truth_on_the_list(auth_client):
    await _loc(auth_client, "Attic")
    pictured = await _loc(auth_client, "Garage")
    await auth_client.post(f"/locations/{pictured['id']}/photo", files=_files(photo_bytes()))

    rows = (await auth_client.get("/locations")).json()
    assert {row["name"]: row["has_photo"] for row in rows} == {"Attic": False, "Garage": True}


async def test_a_rejected_replacement_leaves_the_current_photo_untouched(auth_client):
    """The rejection tests above run against photo-less locations, so they cannot see the
    difference between "validate, then replace" and "replace, then validate". Today the old
    file survives only because the handler validates before photo_store unlinks {id}.* — the
    natural stream-to-disk-first refactor would destroy the previous photo on a 413 with the
    suite green. This pins the contract: a refused upload changes NOTHING."""
    loc = await _loc(auth_client)
    original = photo_bytes()
    r = await auth_client.post(f"/locations/{loc['id']}/photo", files=_files(original))
    assert r.status_code == 200, r.text
    served_before = (await auth_client.get(f"/locations/{loc['id']}/photo")).content

    r = await auth_client.post(
        f"/locations/{loc['id']}/photo",
        files=_files(b"x" * (settings.photo_max_bytes + 1)),
    )
    assert r.status_code == 413
    r = await auth_client.post(
        f"/locations/{loc['id']}/photo", files=_files(b"GIF89a...", "image/gif", "x.gif")
    )
    assert r.status_code == 422

    r = await auth_client.get(f"/locations/{loc['id']}/photo")
    assert r.status_code == 200
    assert r.content == served_before
    assert (await auth_client.get("/locations")).json()[0]["has_photo"] is True
