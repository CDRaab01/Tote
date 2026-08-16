"""Database-level guarantees that live in migration 0001 rather than on a model.

These are asserted against a real Postgres because they cannot be asserted anywhere else: a
functional unique index and a STORED generated column are database behaviour, and mocking them
would only prove the mock works.
"""

import uuid

import pytest
from sqlalchemy.exc import IntegrityError


async def _user(raw_sql) -> str:
    uid = str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO users (id, name, email) VALUES (:i, 'T', :e)",
        i=uid,
        e=f"{uid[:8]}@example.com",
    )
    return uid


async def test_tote_code_is_unique_per_user_case_insensitively(raw_sql):
    """The code is printed on a physical index card and encoded in an NFC tag. Two bins in an
    attic that both claim to be "a14" is a real-world ambiguity, not a tidiness issue — so the
    uniqueness is on lower(code), enforced by the database rather than by a service that could
    be bypassed."""
    uid = await _user(raw_sql)
    await raw_sql(
        "INSERT INTO totes (id, user_id, code) VALUES (:i, :u, 'A14')",
        i=str(uuid.uuid4()),
        u=uid,
    )
    with pytest.raises(IntegrityError):
        await raw_sql(
            "INSERT INTO totes (id, user_id, code) VALUES (:i, :u, 'a14')",
            i=str(uuid.uuid4()),
            u=uid,
        )


async def test_two_users_may_each_have_a_tote_a14(raw_sql):
    """Uniqueness is per user, not global — the codes are handwritten on cards in one house."""
    a, b = await _user(raw_sql), await _user(raw_sql)
    for owner in (a, b):
        await raw_sql(
            "INSERT INTO totes (id, user_id, code) VALUES (:i, :u, 'A14')",
            i=str(uuid.uuid4()),
            u=owner,
        )


async def test_search_vector_is_generated_and_matches_words_from_every_source_column(raw_sql):
    """`search_vector` is a STORED generated column, so it must populate on INSERT with no
    application code involved — and it must cover name, description AND notes, since "where is
    the ratchet set" is as likely to hit a note as a title."""
    uid = await _user(raw_sql)
    item = str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO items (id, user_id, name, description, notes, quantity, status)"
        " VALUES (:i, :u, 'Ratchet set', '3/8 inch drive', 'borrowed from Dave', 1, 'stored')",
        i=item,
        u=uid,
    )
    for term in ("ratchet", "drive", "borrowed"):
        r = await raw_sql(
            "SELECT count(*) FROM items WHERE id = :i"
            " AND search_vector @@ plainto_tsquery('english', :q)",
            i=item,
            q=term,
        )
        assert r.scalar_one() == 1, f"search_vector did not match {term!r}"

    r = await raw_sql(
        "SELECT count(*) FROM items WHERE id = :i"
        " AND search_vector @@ plainto_tsquery('english', 'hammer')",
        i=item,
    )
    assert r.scalar_one() == 0, "search_vector matched a word that is not in the item"


async def test_search_vector_follows_an_update(raw_sql):
    """STORED generated columns are maintained by Postgres on UPDATE too. Asserted because the
    obvious alternative implementation — a trigger, or application code — is the one that
    silently goes stale, and a stale search index on the app's primary query path would look
    like "the item isn't in the catalog"."""
    uid = await _user(raw_sql)
    item = str(uuid.uuid4())
    await raw_sql(
        "INSERT INTO items (id, user_id, name, quantity, status)"
        " VALUES (:i, :u, 'Placeholder', 1, 'stored')",
        i=item,
        u=uid,
    )
    await raw_sql("UPDATE items SET name = 'Soldering iron' WHERE id = :i", i=item)
    r = await raw_sql(
        "SELECT count(*) FROM items WHERE id = :i"
        " AND search_vector @@ plainto_tsquery('english', 'soldering')",
        i=item,
    )
    assert r.scalar_one() == 1


async def test_the_search_index_is_gin(raw_sql):
    """A btree cannot answer "does this document contain these lexemes". If this index were
    ever recreated as the default type, search would still return correct results — just by
    sequential scan — so nothing would fail except performance, silently."""
    r = await raw_sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_items_search_vector'")
    row = r.scalar_one_or_none()
    assert row is not None, "ix_items_search_vector is missing"
    assert "USING gin" in row, f"expected a GIN index, got: {row}"


async def test_deleting_a_tote_does_not_delete_its_items(raw_sql):
    """`items.current_tote_id` is ON DELETE SET NULL, not CASCADE. Throwing a bin away must not
    erase the record of what was in it — the item becomes "not in any tote", which is a true
    statement and a recoverable one."""
    uid = await _user(raw_sql)
    tote, item = str(uuid.uuid4()), str(uuid.uuid4())
    await raw_sql("INSERT INTO totes (id, user_id, code) VALUES (:i, :u, 'Z01')", i=tote, u=uid)
    await raw_sql(
        "INSERT INTO items (id, user_id, name, quantity, status, current_tote_id)"
        " VALUES (:i, :u, 'Tree stand', 1, 'stored', :t)",
        i=item,
        u=uid,
        t=tote,
    )
    await raw_sql("DELETE FROM totes WHERE id = :t", t=tote)
    r = await raw_sql("SELECT current_tote_id FROM items WHERE id = :i", i=item)
    assert r.scalar_one() is None
