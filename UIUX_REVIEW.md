# UI/UX review — 2026-08-21

Reviewed against the committed Roborazzi baselines (`android/app/screenshots/`, ~20 screens
examined in both themes) and the UI source (`ui/navigation/ToteNav.kt`, `ui/components/ItemRow.kt`,
`ui/components/ToteBrand.kt`, `ui/search/SearchScreen.kt`, theme). This is a critique, not a
change set — nothing below has been altered.

## What holds up

- **The identity is real.** Charcoal body, safety-yellow marking, the hazard rule under every
  hero, and the big monospaced bin codes (`A14`) that read like actual label tape. No sibling
  app is mistakable for this one, and the structural contrast with Cookbook that §3 demands is
  visibly there.
- **The empty-state discipline shows.** Loading, empty, unreachable, and "empty but more is
  coming" are genuinely different screens ("3 on the way" with the photograph button removed is
  exactly right). Offline search says it is offline *and* that matching is simpler — provenance
  honesty most apps skip.
- **Navigation is sound.** Five tabs in the right order, back-arrow bar on every pushed route,
  tab navigation that doesn't grow the stack, NFC mismatch surfaced as a fact about *this tap*.
- **The copy voice is the app's best feature.** "Nothing is in a bin until you say it is."
  "Discarding deletes the photographs too." "A label on the wrong box" warnings that say what to
  check before trusting the screen. Warnings state consequences, not policies.
- **The row earns its layout.** Photograph leads, two-line name, two-line description, size as
  its own mark — each decision traceable to a real confusion in a real bin.

## The critique, ranked

### 1. One button style is doing every job — hierarchy has collapsed

The tonal ochre pill (olive fill, yellow label) is the universal control. On `tote_detail_dark`:
Edit bin, Unpack all, Repack all, Select, Write tag, Print card, Add bag, and a Take out / Put
back on every row — ten near-identical yellow pills before the first item is reached. The person
screen, people list, unfiled screen and selection bar are the same. Three costs:

- **No scent for the primary action.** The slate-gradient primary exists (`Add item`, `Snap
  photo`, `File it`) but it is one voice in a choir of yellow; nothing on the bin screen says
  "this is the one you usually want."
- **The safety yellow is spent.** §3 defines yellow as *the marking on the charcoal body* — a
  highlight. As the most common interactive pigment it stops highlighting anything, and there is
  no louder register left short of rose/red.
- **Mode-exit and mutation look identical.** In the selection bar (`tote_detail_selecting_dark`),
  `Done` (leave selection mode) is pixel-for-pixel the same treatment as `Take out` (moves
  every ticked item). One of these is safe and one writes ledger rows.

**Recommendation:** introduce a genuinely quiet secondary treatment (outlined or text button,
neutral label) and demote management verbs (Write tag, Print card, Add bag, Select, Edit bin) to
it. Budget tonal yellow at roughly one or two per screen. In light mode this also fixes the
`New tote` cream-on-near-white pill (`totes_light`), whose fill barely separates from the page.

### 2. The bin screen buries its answer

The flagship interaction is: tap the tag, see what's inside. On `tote_detail_dark` the first
item appears roughly 60% down the screen, below the hero, two management buttons, three bulk
buttons, and the not-labelled panel. Setup-time chrome permanently outranks the read-time
payload — and in selection mode the whole stack stays put above the list it is selecting from.

**Recommendation:** contents directly under the hero. The not-labelled / mismatch panels have
earned their early slot (they are about trusting what's below); the bulk verbs and tag/card
tools have not — fold them into a compact toolbar or the top app bar. Same argument, smaller
scale, for the capture screen's permanent "Scanning books?" card: a niche flow holding the top
slot of the app's second-most-used screen. One line of link-styled text would do.

### 3. Rose is over-assigned, which blunts the one channel that must stay sharp
> **Addressed 2026-08-26 (#61)** — Home's OUT tile is now "No bin" and speaks electric
> blue: it is a cross-reference, not an alarm. The unfiled screen's own header and
> `NO LOCATION YET` were left rose — flagged, not fixed.


§3: rose = *needs you*. But Home's `OUT` stat tile is rose always (`search_idle_dark`), and
"out" is a normal, frequently deliberate state — unpacked for the season, lent on time. The
placeless-bins header (`NO LOCATION YET`) and the unfiled screen header are rose too. Meanwhile
the states that genuinely need the channel — overdue loans, stuck uploads, tag mismatch — have
to share it with routine facts. The gate on the channel's value is that it is rare.

**Recommendation:** the OUT tile speaks neutral (or electric blue — it is a cross-reference,
not a problem); placeless bins likewise. Rose keeps: overdue, mismatch, stuck, unfiled-drafts.

### 4. Attention surfaces name problems but don't open them
> **Addressed 2026-08-26 (#61)** — on the Find tab. Overdue rows open the item sheet, the
> Totes and No-bin tiles open their screens, and card swatches open their bin. The Items
> tile stays inert deliberately: a count of everything is not a problem and there is no
> all-items screen. `SearchTapTest` presses the pixels so this cannot silently rot.


- The overdue card (`search_overdue_dark`) names the drill, Dave, and the date — and is inert.
  No tap-through to the item sheet (where Return lives) or the person. Acting on it means
  re-typing "drill" into search.
- The stat tiles aren't tappable, and `OUT 6` has **no corresponding list anywhere** — out-ness
  is only visible scattered per-bin and per-person. The number invites the question the app
  can't answer in one place.
- Review's stuck strip says "sort the stopped ones out on Catalogue" — a sentence pointing at a
  tab, instead of being the tap that goes there.

The app already has the rule "an empty screen must say why." Its sibling: **a surface that
names a problem must open it.** Overdue rows → item sheet; OUT tile → a global out/loaned
list; stuck strip → Catalogue.

### 5. All-caps letterspaced caption style is being used for sentences

The caps caption is right for `IN THIS TOTE`. It is wrong for the four-line tag-mismatch body
(`tote_detail_mismatch_dark`) — the most safety-critical paragraph in the app set in its least
readable style — and for "WRITE A TAG OR PRINT A CARD SO THIS BIN CAN BE FOUND", the three-line
sign-out explanation, the discard warning, and "5 ITEMS WAITING FOR SOMEWHERE TO GO" (which
wraps to an orphaned "GO" beside the Select button on `unfiled_dark`). `SearchScreen.kt` line
207 already states the correct rule — caption "is right for a label and wrong for a sentence —
it shouts, and it wraps badly." Apply it app-wide: any explanation longer than ~3 words is
sentence-case body text.

### 6. `ItemRow`: the size mark collides with the action button

`ItemRow.kt` inserts a spacer *before* the size mark (line 156) but none between it and the
trailing button (line 172) — visible on `unfiled_dark.png`, where violet `12m` abuts `File…`.
One missing `Spacer(Modifier.width(spacing.sm))`. While in there: the mark also reads as
*part of* the button at a glance; trailing it above/below the button line would separate the
fact from the verb.

### 7. Destructive actions hold standing prominence

The item sheet's `Delete item` is a full-width red button, the heaviest element on the sheet,
present on every open — for the app's only unrecoverable act (photographs). The person screen
pairs `Remove` at equal weight beside `Edit` at the very top. Both are confirmed, but weight
invites consideration. Convention worth adopting: destructive verbs are text-styled, last, and
visually small — the confirm dialog carries the gravity, the button shouldn't advertise.

### 8. Machine formats leak into sentences
> **Partly addressed 2026-08-26 (#61)** — the overdue card reads "due Aug 1" via
> `formatDue`. `ItemRow`'s "since {expectedBack}" is still ISO.


"due 2026-08-01" on the overdue card; "since {expectedBack}" in row status. The copy voice is
warm everywhere else — ISO dates read like a debug build. Format as "due Aug 1."

## Smaller observations

- **Review chooser:** the unnamed draft's tile shows a bare centred `5` (its photo count) —
  reads as a mystery number; caption it ("5 photos").
- **Review screen:** `File it` sits below a long form, then Back/Skip/Discard below that — a
  fully-edited draft means scrolling to file. A sticky bottom action bar would keep the primary
  verb on-screen; twenty drafts is twenty scrolls.
- **Home idle state** is mostly empty below the tiles once the browse chips are absent. Cheap
  wins for the returning user: "4 drafts waiting" shortcut, recent activity line.
- **`person_fits`:** the garment-type chips re-ask the server (good), but `Everything` selected
  state is the stored-green fill while every other chip set uses neutral selection — one chip
  row speaking a channel the others don't.
- **Light mode** generally holds up (charcoal-led, cream containers, ratios per `ToteThemeTest`),
  with the low-affordance `New tote` noted above.

## What was explicitly checked and is fine

- Bottom-bar badges: attention-rose, on the two tabs whose work stalls silently — correct use
  of the channel. Badge counts distinguish drafts (Review) from stuck uploads (Catalogue).
- Tap targets and one-gesture-per-row (`ItemRow`'s single `combinedClickable`) — the #38 rule
  is holding.
- `HazardRule` documented as decorative-only, never state-bearing.
- Channel ticks on section headers always accompany words; color never carries meaning alone.
