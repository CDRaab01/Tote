"""households — the catalogue becomes shared property

Written by hand, NOT by autogenerate, for the reason recorded in 0002-0005: autogenerate cannot
see `ix_items_search_vector` or `uq_totes_user_code_lower` in the model metadata and proposes
dropping both, every single time. Every constraint below is named so `downgrade` can drop it.

## What this migration actually does

It moves the ownership axis of the whole catalogue from a *user* to a *household*, then rescopes
every uniqueness constraint that was per-user onto the household. The order below is
load-bearing and each step is only safe because of the one before it:

1. Create `households` / `household_members` / `household_invites`.
2. Give **every existing user a household of one** and make them its owner. This is what lets
   the new `household_id` columns be NOT NULL without a nullable interlude that the application
   would have to defend against forever.
3. Add `household_id` to the six catalogue tables, back-fill each row from its `user_id`, then
   set NOT NULL.
4. Rescope the four uniqueness constraints. **Safe only because of step 2**: while every
   household holds exactly one user, per-user and per-household uniqueness are the same
   constraint, so nothing can collide at this instant. Merging two populated households later
   is where collisions become possible, and that is checked in application code before any row
   moves (`services/household_service.merge_conflicts`) — a constraint cannot express "ask a
   human to go and look in the attic".
5. Relax `user_id` to nullable with ON DELETE SET NULL. It stops meaning *who may see this* and
   starts meaning only *who created it*; a shared catalogue must not lose a member's bins when
   their account goes away.
6. Add `movements.moved_by_user_id` — a question that does not exist until the catalogue is
   shared, and which the ledger had no column for.
7. Move `nfc_uri_base` from `user_settings` to `households`. It was never read, so this costs
   nothing today, but per-user it was wrong by construction: two members writing different bases
   produce tags that open for one person and not the other.

## Downgrade

Reverses all of it and is lossy in exactly one way, which is unavoidable and worth stating: a
household holding **more than one member** cannot be expressed per-user, so rows are returned to
their creator (`user_id`). Anything created by a member who has since been deleted has a null
`user_id` and cannot be placed at all — the downgrade fails loudly rather than silently
discarding it or parking it on an arbitrary account.

Revision ID: 0006
Revises: 0005
Create Date: 2026-08-18

"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# The six tables whose ownership axis moves. Listed once so the six near-identical column
# operations below cannot drift apart.
CATALOGUE_TABLES = ("totes", "items", "locations", "categories", "people", "containers")

# The existing `user_id` foreign keys, by the names Postgres and earlier migrations actually gave
# them — verified against the live schema, not assumed. Five carry the default
# `{table}_user_id_fkey`; `containers` does not, because 0005 named its constraints explicitly
# (0002's rule, applied from that migration onward). Guessing a uniform name here fails at
# `DROP CONSTRAINT` on exactly one of the six.
CREATOR_FK = {t: f"{t}_user_id_fkey" for t in CATALOGUE_TABLES} | {
    "containers": "fk_containers_user_id"
}


def upgrade() -> None:
    # --- 1. The new tables -----------------------------------------------------------------
    op.create_table(
        "households",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("owner_user_id", sa.Uuid(), nullable=False),
        sa.Column("nfc_uri_base", sa.String(length=255), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        # RESTRICT, not CASCADE: the catalogue hangs off this row, so a cascade from a user
        # deletion would take an entire household inventory with it.
        sa.ForeignKeyConstraint(
            ["owner_user_id"], ["users.id"], name="fk_households_owner", ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_households"),
    )
    op.create_index("ix_households_owner_user_id", "households", ["owner_user_id"])

    op.create_table(
        "household_members",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("household_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["household_id"], ["households.id"], name="fk_members_household", ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name="fk_members_user", ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_household_members"),
        # One household per user. The whole "every user always has exactly one" invariant that
        # `User.household_id` relies on rests on this line.
        sa.UniqueConstraint("user_id", name="uq_household_members_user"),
    )
    op.create_index("ix_household_members_household_id", "household_members", ["household_id"])

    op.create_table(
        "household_invites",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("household_id", sa.Uuid(), nullable=False),
        sa.Column("invited_user_id", sa.Uuid(), nullable=False),
        sa.Column("invited_by_user_id", sa.Uuid(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["household_id"], ["households.id"], name="fk_invites_household", ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["invited_user_id"], ["users.id"], name="fk_invites_invited", ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["invited_by_user_id"], ["users.id"], name="fk_invites_inviter", ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_household_invites"),
        sa.UniqueConstraint("invited_user_id", name="uq_household_invites_invited"),
    )
    op.create_index("ix_household_invites_household_id", "household_invites", ["household_id"])
    op.create_index(
        "ix_household_invites_invited_user_id", "household_invites", ["invited_user_id"]
    )

    # --- 2. A household of one for every existing user --------------------------------------
    # `gen_random_uuid()` is pgcrypto, in core Postgres since 13. One statement rather than a
    # Python loop so this holds however many accounts exist, and so it is a single transaction.
    op.execute(
        """
        INSERT INTO households (id, owner_user_id, created_at)
        SELECT gen_random_uuid(), u.id, now() FROM users u
        """
    )
    op.execute(
        """
        INSERT INTO household_members (id, household_id, user_id, created_at)
        SELECT gen_random_uuid(), h.id, h.owner_user_id, now() FROM households h
        """
    )

    # --- 3. household_id on the catalogue ---------------------------------------------------
    for table in CATALOGUE_TABLES:
        op.add_column(table, sa.Column("household_id", sa.Uuid(), nullable=True))
        op.execute(
            f"""
            UPDATE {table} t
               SET household_id = m.household_id
              FROM household_members m
             WHERE m.user_id = t.user_id
            """
        )
        op.alter_column(table, "household_id", nullable=False)
        op.create_foreign_key(
            f"fk_{table}_household",
            table,
            "households",
            ["household_id"],
            ["id"],
            ondelete="CASCADE",
        )
        op.create_index(f"ix_{table}_household_id", table, ["household_id"])

    # --- 4. Rescope uniqueness (see the module docstring for why this is safe here) ---------
    op.drop_constraint("uq_categories_user_name", "categories", type_="unique")
    op.create_unique_constraint(
        "uq_categories_household_name", "categories", ["household_id", "name"]
    )
    op.drop_constraint("uq_locations_user_name", "locations", type_="unique")
    op.create_unique_constraint(
        "uq_locations_household_name", "locations", ["household_id", "name"]
    )
    op.drop_constraint("uq_totes_user_tag", "totes", type_="unique")
    op.create_unique_constraint("uq_totes_household_tag", "totes", ["household_id", "nfc_tag_uid"])
    op.drop_constraint("uq_items_user_capture", "items", type_="unique")
    op.create_unique_constraint(
        "uq_items_household_capture", "items", ["household_id", "capture_id"]
    )
    # The functional index autogenerate cannot see. Dropped and recreated on the new axis, under
    # a new name so a half-applied migration is obvious rather than ambiguous.
    op.drop_index("uq_totes_user_code_lower", table_name="totes")
    op.create_index(
        "uq_totes_household_code_lower",
        "totes",
        ["household_id", sa.text("lower(code)")],
        unique=True,
    )

    # --- 5. user_id becomes provenance ------------------------------------------------------
    for table in CATALOGUE_TABLES:
        op.alter_column(table, "user_id", nullable=True)
        op.drop_constraint(CREATOR_FK[table], table, type_="foreignkey")
        op.create_foreign_key(
            f"fk_{table}_creator", table, "users", ["user_id"], ["id"], ondelete="SET NULL"
        )

    # --- 6. Who moved it --------------------------------------------------------------------
    op.add_column("movements", sa.Column("moved_by_user_id", sa.Uuid(), nullable=True))
    op.create_foreign_key(
        "fk_movements_moved_by",
        "movements",
        "users",
        ["moved_by_user_id"],
        ["id"],
        ondelete="SET NULL",
    )

    # --- 7. nfc_uri_base is a household fact ------------------------------------------------
    op.execute(
        """
        UPDATE households h
           SET nfc_uri_base = s.nfc_uri_base
          FROM user_settings s
         WHERE s.user_id = h.owner_user_id AND s.nfc_uri_base IS NOT NULL
        """
    )
    op.drop_column("user_settings", "nfc_uri_base")


def downgrade() -> None:
    op.add_column("user_settings", sa.Column("nfc_uri_base", sa.String(length=255), nullable=True))
    op.execute(
        """
        UPDATE user_settings s
           SET nfc_uri_base = h.nfc_uri_base
          FROM households h
         WHERE h.owner_user_id = s.user_id AND h.nfc_uri_base IS NOT NULL
        """
    )

    op.drop_constraint("fk_movements_moved_by", "movements", type_="foreignkey")
    op.drop_column("movements", "moved_by_user_id")

    # A row whose creator was deleted has nowhere to go back to: `user_id` is null and the
    # per-user model has no way to express "belongs to the household". Refuse rather than drop
    # it or park it on whichever account sorts first.
    for table in CATALOGUE_TABLES:
        op.execute(
            f"""
            DO $$
            BEGIN
                IF EXISTS (SELECT 1 FROM {table} WHERE user_id IS NULL) THEN
                    RAISE EXCEPTION
                        '{table} has rows with no creator; they cannot be returned to a user';
                END IF;
            END $$;
            """
        )

    op.drop_index("uq_totes_household_code_lower", table_name="totes")
    op.create_index(
        "uq_totes_user_code_lower", "totes", ["user_id", sa.text("lower(code)")], unique=True
    )
    op.drop_constraint("uq_items_household_capture", "items", type_="unique")
    op.create_unique_constraint("uq_items_user_capture", "items", ["user_id", "capture_id"])
    op.drop_constraint("uq_totes_household_tag", "totes", type_="unique")
    op.create_unique_constraint("uq_totes_user_tag", "totes", ["user_id", "nfc_tag_uid"])
    op.drop_constraint("uq_locations_household_name", "locations", type_="unique")
    op.create_unique_constraint("uq_locations_user_name", "locations", ["user_id", "name"])
    op.drop_constraint("uq_categories_household_name", "categories", type_="unique")
    op.create_unique_constraint("uq_categories_user_name", "categories", ["user_id", "name"])

    for table in CATALOGUE_TABLES:
        op.drop_constraint(f"fk_{table}_creator", table, type_="foreignkey")
        op.create_foreign_key(
            CREATOR_FK[table], table, "users", ["user_id"], ["id"], ondelete="CASCADE"
        )
        op.alter_column(table, "user_id", nullable=False)
        op.drop_index(f"ix_{table}_household_id", table_name=table)
        op.drop_constraint(f"fk_{table}_household", table, type_="foreignkey")
        op.drop_column(table, "household_id")

    op.drop_index("ix_household_invites_invited_user_id", table_name="household_invites")
    op.drop_index("ix_household_invites_household_id", table_name="household_invites")
    op.drop_table("household_invites")
    op.drop_index("ix_household_members_household_id", table_name="household_members")
    op.drop_table("household_members")
    op.drop_index("ix_households_owner_user_id", table_name="households")
    op.drop_table("households")
