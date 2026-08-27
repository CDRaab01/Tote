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
    # How many bins the count actually spans. The swatch list is capped and the count is not, so
    # without this the card showed a handful of glyphs beside a number covering more bins than
    # it drew — somebody could visit every swatch and still come up short. Additive with a
    # default, so an older client simply draws no overflow mark.
    tote_count: int = 0


class NextSizeCard(BaseModel):
    """A person is nearing the next size band, and the catalogue already holds garments in it.

    Built on the recorded size history and the ladder — never on age guesses.

    **`next_label` is the counted garments' own most common tag**, not the ladder's table key,
    and that is a deliberate reversal of what this docstring used to promise. One rung has
    several spellings (`12-18M` and `15M` are one ordinal), so a card could name a rung correctly
    and still print words appearing on nothing in the bin. Deriving the label from the rows the
    count came from makes the two describe one set by construction.

    `garment_count` and `totes` cover items currently STORED whose parsed size sits at that one
    rung — `rung_band`, not a fixed tolerance; see services/home.py for why that distinction cost
    a wrong card in production. `tote_count` is how many bins that is, before the list is capped.
    """

    person_id: uuid.UUID
    person_name: str
    next_label: str
    garment_count: int
    # Up to three bin references, most items first — enough to say where to go.
    totes: list[SeasonalTote]
    # How many bins the count actually spans. The swatch list is capped and the count is not, so
    # without this the card showed a handful of glyphs beside a number covering more bins than
    # it drew — somebody could visit every swatch and still come up short. Additive with a
    # default, so an older client simply draws no overflow mark.
    tote_count: int = 0


class HomeOut(BaseModel):
    seasonal: SeasonalCard | None = None
    next_size: NextSizeCard | None = None
