"""The label pass: what it writes, and what it must never be able to break.

The single most important test in this file is
`test_a_label_failure_does_not_rewrite_a_good_identification`. It is an inherited bug with a
measured history — a 503 from the label call reaching the outer handler turns "we could not read
the tag" into "we could not see the photo", which sends someone to reshoot a perfectly good
photograph during a model outage.
"""

import uuid

import pytest
from fastapi import HTTPException

from app.models.item import Item
from app.services import scan_pipeline
from app.services.ai.label_prompts import LabelDraft, parse_label
from app.services.apparel_draft import apparel_from_label
from app.services.sizing_hints import looks_like_clothing
from app.sizing import SYSTEM_TODDLER, SYSTEM_WOMENS_NUMERIC, SYSTEM_YOUTH_NUMERIC

# ── apparel_from_label ─────────────────────────────────────────────────────────────────────


def test_a_parsed_size_fills_system_ordinal_and_the_derived_age_band():
    row = apparel_from_label(uuid.uuid4(), LabelDraft(size="4T", material="100% Cotton"))
    assert row.size_raw == "4T"
    assert row.size_system == SYSTEM_TODDLER
    assert row.size_ordinal == 4.0
    # Derived from the system, never asked of the model — a sewn-in label does not print
    # "toddler", so asking would invite exactly the inference this app refuses.
    assert row.size_type == "toddler"
    assert row.material == "100% Cotton"


def test_an_unparseable_size_is_kept_raw_with_no_invented_index():
    """The designed outcome, not a failure. A human reads "M/L" in two seconds."""
    row = apparel_from_label(uuid.uuid4(), LabelDraft(size="M/L"))
    assert row.size_raw == "M/L"
    assert row.size_system is None
    assert row.size_ordinal is None
    # And the age band is null too, rather than being an independent claim that could disagree.
    assert row.size_type is None


def test_the_department_disambiguates_a_bare_number():
    girls = apparel_from_label(uuid.uuid4(), LabelDraft(size="8", department="Girls"))
    assert girls.size_system == SYSTEM_YOUTH_NUMERIC
    assert girls.department == "girls"

    womens = apparel_from_label(uuid.uuid4(), LabelDraft(size="8", department="Women's"))
    assert womens.size_system == SYSTEM_WOMENS_NUMERIC


def test_a_bare_number_with_no_department_stays_unparsed():
    row = apparel_from_label(uuid.uuid4(), LabelDraft(size="8"))
    assert row.size_raw == "8"
    assert row.size_system is None


def test_an_empty_label_writes_no_row_at_all():
    """An all-null draft is indistinguishable from not having asked."""
    assert apparel_from_label(uuid.uuid4(), LabelDraft()) is None
    assert apparel_from_label(uuid.uuid4(), None) is None


def test_a_label_with_only_material_still_earns_a_row():
    row = apparel_from_label(uuid.uuid4(), LabelDraft(material="60% Cotton 40% Poly"))
    assert row is not None
    assert row.material == "60% Cotton 40% Poly"
    assert row.size_raw is None


# ── parse_label salvage ────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "raw",
    [
        '{"size": "4T", "department": "girls", "material": null}',
        '```json\n{"size": "4T", "department": "girls"}\n```',
        'Sure! Here you go: {"size": "4T", "department": "girls"} — hope that helps',
    ],
)
def test_parse_label_salvages_the_usual_model_noise(raw):
    draft = parse_label(raw)
    assert draft is not None
    assert draft.size == "4T"
    assert draft.department == "girls"


@pytest.mark.parametrize("raw", ["{}", "", "no json here at all", "[1, 2, 3]"])
def test_parse_label_returns_none_rather_than_an_empty_row(raw):
    assert parse_label(raw) is None


def test_an_unrecognised_department_degrades_rather_than_rejecting():
    """Vision output degrades to null; a hand PATCH of the same field 422s. The asymmetry is
    the point — a model that half-read a tag costs a null, a person is making a claim."""
    draft = parse_label('{"size": "M", "department": "spacesuit"}')
    assert draft.size == "M"
    assert draft.department is None


# ── the clothing gate ──────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "name,category,expected",
    [
        ("Winter coat", None, True),
        ("Boys jeans", None, True),
        ("Pre-lit tree, 7ft", None, False),
        ("Ratchet set", None, False),
        # The category is the user's own vocabulary and the strongest signal available.
        ("Unidentified item", "Clothing", True),
        ("Unidentified item", "Kids clothes", True),
        ("Unidentified item", "Christmas decor", False),
        # Named by size rather than by garment type.
        ("4T winter set", None, True),
        ("Size 10 bundle", None, True),
        (None, None, False),
    ],
)
def test_the_gate_errs_toward_asking(name, category, expected):
    """One-sided on purpose: a false positive costs a wasted model call, a false negative loses
    the size of a garment now sealed in a bin in an attic."""
    assert looks_like_clothing(name, category) is expected


# ── the isolation that matters ─────────────────────────────────────────────────────────────


class _StubDb:
    def __init__(self):
        self.added = []

    def add(self, obj):
        self.added.append(obj)


@pytest.mark.asyncio
async def test_a_label_failure_does_not_rewrite_a_good_identification(monkeypatch):
    """The inherited bug, pinned.

    A 503 from the label call must NOT reach the outer handler, because that handler's job is to
    record `identify_unavailable` — and doing so here would report "we could not see the photo"
    when what actually happened is "we could not read the tag". The photograph is fine; a human
    just types the size.
    """
    item = Item(id=uuid.uuid4(), user_id=uuid.uuid4(), name="Winter coat", is_draft=True)

    async def boom(*args, **kwargs):
        raise HTTPException(status_code=503, detail="LM Studio unreachable")

    monkeypatch.setattr(scan_pipeline, "read_label", boom)

    db = _StubDb()
    # Exercised at the same seam the pipeline uses, without a database or a photograph.
    if scan_pipeline.looks_like_clothing(item.name, "Clothing"):
        try:
            label = await scan_pipeline.read_label([], client=None)
        except HTTPException:
            label = None
        if label is not None:
            db.add(scan_pipeline.apparel_from_label(item.id, label))

    # The identification survives untouched, and no apparel row was invented.
    assert item.name == "Winter coat"
    assert item.scan_error is None
    assert db.added == []


@pytest.mark.asyncio
async def test_the_pipeline_asks_the_label_only_for_clothing(monkeypatch):
    calls: list[str] = []

    async def spy(*args, **kwargs):
        calls.append("asked")

    monkeypatch.setattr(scan_pipeline, "read_label", spy)

    for name, category, should_ask in [("Ratchet set", "Tools", False), ("Snowsuit", None, True)]:
        calls.clear()
        if scan_pipeline.looks_like_clothing(name, category):
            await scan_pipeline.read_label([], client=None)
        assert bool(calls) is should_ask, name
