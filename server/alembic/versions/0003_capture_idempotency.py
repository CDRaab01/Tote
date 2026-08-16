"""capture_id on items — makes a replayed scan resolve to the draft it already created

Written by hand, NOT by autogenerate, for the reason recorded in 0002: autogenerate cannot see
`ix_items_search_vector` or `uq_totes_user_code_lower` in the model metadata and proposes
dropping both, every single time. `test_schema.py` asserts they survive at head.

Revision ID: 0003
Revises: 0002
Create Date: 2026-08-16

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0003'
down_revision: Union[str, None] = '0002'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('items', sa.Column('capture_id', sa.Uuid(), nullable=True))
    # Per user rather than global: capture ids come from the phone. NULLs are distinct in a
    # Postgres unique constraint, so manually-added items are untouched by this.
    op.create_unique_constraint('uq_items_user_capture', 'items', ['user_id', 'capture_id'])


def downgrade() -> None:
    op.drop_constraint('uq_items_user_capture', 'items', type_='unique')
    op.drop_column('items', 'capture_id')
