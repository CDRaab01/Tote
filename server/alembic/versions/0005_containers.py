"""containers — bags inside a tote, and items' optional membership of one

Written by hand, NOT by autogenerate, for the reason recorded in 0002, 0003 and 0004: autogenerate
cannot see `ix_items_search_vector` or `uq_totes_user_code_lower` in the model metadata and
proposes dropping both, every single time. It also emits unnamed foreign keys that `downgrade`
cannot then drop, so every constraint below is named. `test_schema.py` asserts the two indexes
survive at head.

The two ON DELETE rules are the design, not defaults:

* `containers.tote_id` → **CASCADE**. A bag has no meaning outside the bin it is in.
* `items.container_id` → **SET NULL**. Deleting a bin loses the grouping and never the contents,
  which is the same promise `items.current_tote_id` already makes.

Revision ID: 0005
Revises: 0004
Create Date: 2026-08-17

"""

from typing import Sequence, Union

import sqlalchemy as sa

from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0005"
down_revision: Union[str, None] = "0004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "containers",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("tote_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=80), nullable=False),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False
        ),
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name="fk_containers_user_id", ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["tote_id"], ["totes.id"], name="fk_containers_tote_id", ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_containers"),
    )
    op.create_index("ix_containers_user_id", "containers", ["user_id"])
    op.create_index("ix_containers_tote_id", "containers", ["tote_id"])

    op.add_column("items", sa.Column("container_id", sa.Uuid(), nullable=True))
    op.create_foreign_key(
        "fk_items_container_id", "items", "containers", ["container_id"], ["id"], ondelete="SET NULL"
    )
    op.create_index("ix_items_container_id", "items", ["container_id"])


def downgrade() -> None:
    op.drop_index("ix_items_container_id", table_name="items")
    op.drop_constraint("fk_items_container_id", "items", type_="foreignkey")
    op.drop_column("items", "container_id")

    op.drop_index("ix_containers_tote_id", table_name="containers")
    op.drop_index("ix_containers_user_id", table_name="containers")
    op.drop_table("containers")
