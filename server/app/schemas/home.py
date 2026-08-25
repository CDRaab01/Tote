"""The Find tab's volunteered cards — the two things the app says unprompted.

Both are read-side compositions over data the catalogue already holds; neither invents. The
client renders exactly what arrives (clients display, never compute), and a null card is simply
absent from the screen — the "0 out is hidden" rule.
"""

import datetime
import uuid

from pydantic import BaseModel


class SeasonalTote(BaseModel):
    id: uuid.UUID
    code: str
    color_hex: str | None = None


class SeasonalCard(BaseModel):
    """Around this time last year these bins were unpacked — they'll be wanted again soon.

    Grounded entirely in the ledger: totes whose contents recorded `unpacked` movements in the
    window [today-1y, today-1y+8w]. No holiday calendar, no category hardcoding — the user's own
    unpacking is the signal, and `category_name` (present only when every bin agrees) lets the
    client title the card in the user's own vocabulary.
    """

    totes: list[SeasonalTote]
    # The single location shared by the bins, when they agree; null when they are spread out.
    location_name: str | None = None
    # The date the unpacking STARTED last year — "last year you unpacked them Nov 28".
    unpacked_on: datetime.date
    item_count: int
    # The bins' shared category name, only when unanimous (e.g. "Christmas / seasonal decor").
    category_name: str | None = None


class NextSizeCard(BaseModel):
    """A person is nearing the next size band, and the catalogue already holds garments in it.

    Built on the recorded size history and the ladder's next_size_up — never on age guesses.
    `next_label` is a ladder rung label (e.g. "12-18M"); counts and bins cover items currently
    STORED whose parsed size sits in that band, cross-system matches included on the ladder's
    own approximate terms.
    """

    person_id: uuid.UUID
    person_name: str
    next_label: str
    garment_count: int
    # Up to three bin references, most items first — enough to say where to go.
    totes: list[SeasonalTote]


class HomeOut(BaseModel):
    seasonal: SeasonalCard | None = None
    next_size: NextSizeCard | None = None
