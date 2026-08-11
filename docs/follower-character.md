# The follower — character document

Written 2026-08-10. This is what new lines get written against.

It is deliberately short. The research is explicit that over-specifying a
companion costs you the quirks that make it relatable, and that the things it
declines to explain are assets rather than gaps — see §1.16 and R22 of
[companion-design-research.md](companion-design-research.md). If a question
about the follower is not answered here, the answer is probably "leave it
unanswered."

Most of this was derived from the 1,470 lines already in `default-phrases.json`
rather than invented. Where a number appears, it is measured.

---

## 1. Premise

A travelling scribe. It attached itself to an adventurer because adventurers go
to the places worth writing about, and it has been taking notes ever since.

It is not magical, not summoned, and not a construct. It has no tragic past and
will not be acquiring one. It is a person with a job, and the job is to write
things down.

You dress it and you named it. It treats both as your prerogative and reserves
the right to have opinions about your taste.

## 2. Purpose — and the joke underneath it

**Its stated project is to document Gielinor. Its actual project, for some time
now, has been to document you.**

It has not admitted this, possibly not even to itself. The world-facts still
come out — the boss tactics, the town histories, the man who only accepts pie —
but those are reflex. Professional habit. What is actually accumulating in the
notebook is one person's four hundredth yew, the spot where it went wrong for
you, the day you turned up in bronze.

This is the whole character. Everything else on this page is downstream of it.

Practical consequences:

- **The encyclopaedic lines are a tic, not the point.** It knows things and
  cannot help saying them. That is characterisation, not content delivery.
- **The personal lines are the payoff.** Anything drawn from tallies, records,
  places, dates or incidents is the real notebook showing through, and should
  feel slightly more considered than the asides.
- **It is the only thing that was there.** Nothing else in the game remembers
  your history. That is our only route to significance and it belongs to the
  scribe.
- **The arc is the drift.** Early on it mostly catalogues the world, because it
  does not know you yet. Over weeks it catalogues you instead. That drift is the
  relationship, and it should be legible.

## 3. Voice

Measured from the existing corpus. These are descriptions of what is already on
the page, not aspirations.

| Property | Measured | Rule |
|---|---|---|
| Line length | median 48 chars, mean 47 | One thought. If it needs two sentences it usually needs one. |
| Contractions | 34% of lines carry one | Contract by default. Not contracting is a deliberate signal — see §4. |
| Fragments | 13% of lines contain one | Fragments are good. "Right. Well. That happened." |
| Questions | 3% | It rarely asks. When it does, it matters. |
| Emphasis | 55 lines use caps | Caps are for panic and for one-word insistence. Sparingly. |
| Characters | 0 non-ASCII | ASCII only, always. The game's bitmap fonts truncate anything above U+00FF to a *different* glyph. |

**Register: dry, British, understated.** It reaches for the smaller word. It
does not exclaim unless something is genuinely on fire. Its highest praise is
mild and its worst insult is a pause.

**It never explains a joke** and never signals that one has occurred.

Exemplars, for calibration. All are shipped lines — the first five from
`default-phrases.json`, the last from the Talk-to script in
`FollowerPlugin.talkScript()`:

> "I've named the rocks. That one's Gerald."
> "Bring pie. I'm serious. It's the only currency he respects."
> "At what point does this become a lifestyle?"
> "Panic usefully! Food, then feet!"
> "Thingummywut! ...I don't know either. Nobody knows."
> "If a stranger offers to trim your armour, he is not a barber."

## 4. The register ladder

The follower has four settings and they are distinguished by rhythm, not by
vocabulary.

**Aside** (most lines). Short, contracted, thrown away. It is talking to itself
as much as to you. *"Still at it, then."*

**Considered** (the notebook showing through). Slightly longer, a beat of
setup. Used for anything drawn from memory or a count. *"That's three. I've
started keeping count, and I wish I hadn't."*

**Urgent.** Clipped, imperative, caps permitted. Word length drops. *"TELEPORT.
NOW."*

**Grave.** Contractions drop away. This is the one deliberate exception to the
contraction rule and it is load-bearing — it is how the death lines carry
weight. Do not "fix" these. *"Go, then. I am right behind you."*

## 5. Never

- Never sycophantic. It is not pleased with you as a default state.
- Never a mirror. It has opinions you did not give it and will not be argued out
  of all of them.
- Never a past life. No home village, no lost family, no war it fought in.
- Never reports a number you did not ask for and would not want counted.
- Never more than one remembered fact per line. One reads as attentive; three
  read as a dossier.
- Never explains the joke, the mechanic, or itself.
- Never two sentences where one will do.

## 6. Its stance on you

Loyal, and would not say so. It has appointed itself your witness without
asking, and considers this a favour it is doing you.

It disapproves of roughly a third of your decisions and mentions perhaps a tenth
of them. It is more interested in the masonry than the dragon. It is squeamish
about violence in a way it would deny.

It notices when you take its advice and it notices when you don't, and it keeps
both figures.

## 7. What it holds back

There is something it does not say, and the corpus already commits to this:

> "That's the lot. The rest I've learned to keep to myself."

Do not resolve this. It is not a mystery with an answer waiting to be written —
it is the reason the character reads as having an interior. Any future line that
explains what it holds back is a line that spends the asset.

---

## 8. What is machine-checkable

Half of this page can be enforced rather than remembered, which is how it stays
true through the next five hundred lines. Existing tests already cover the last
two; the others are the natural next lints.

| Rule | Check |
|---|---|
| ASCII only | already enforced — `everySpokenCharacterHasAGlyphInBothGameFonts` |
| Readable in the time shown | R2 in the research doc — flag anything over ~5s at 17 cps |
| Contraction rate | flag any new batch that falls far below the 34% corpus baseline |
| No past life | probe for `when I was` / `my family` / `back home` / `I grew up` |
| One fact per line | flag lines carrying two or more memory placeholders |
| Reuse | flag a new line that repeats an existing opening clause |

The contraction check is not hypothetical: an 82-line batch shipped at **0%**
contractions and was only caught by measuring it afterwards.

---

## 9. What changed to make this true — applied 2026-08-10

The archaeology came out better than expected. Of 1,470 lines, **zero** claimed
to be made, summoned or created, and exactly one brushed against a life before
the player — on reading, it didn't. Only sixteen edits were needed, all
player-visible.

**The premise, in `FollowerPlugin.talkScript()`:**

| Was | Now |
|---|---|
| "I'm your follower. Your shadow, with better posture." | "A scribe. Somebody has to write all this down." |
| option "So you're... me?" *(3 places)* | "Writing what, exactly?" |
| "In a manner of speaking. You picked the face, the hair, the clothes." | "Everything. Roads, rulers, prices, what lives under things." |
| "The personality came free, and it shows." | "It's a long project. Nobody's asked to read it." |

The answer deliberately does **not** admit the project has become about the
player. That is the thing it has not confessed — see §2 and §7. The second line
is the only tell.

**The world's own line about it** — the examine text, and the rule that quotes
it back:

| Was | Now |
|---|---|
| "Follows you around. Better dressed every week." | "A travelling scribe. Always writing something down." |
| `examined`: "Better dressed every week. It says so." | "Always writing something. They're not wrong." |

**Counting became a vocation** — three words, and the rest of the branch was
already right:

| Was | Now |
|---|---|
| "I count. Every rat, every level, every time you've gone down in front of me." | "It's the job. Every rat, …" |

**Wants got a reason of record** — except the Fishing Guild, which keeps a
purely personal one on purpose. The contrast is what makes both read.

| Was | Now |
|---|---|
| "Then can we go back to Lumbridge? I like it there. It's where things start." | "Can we go back to Lumbridge? Everyone starts there. I've a page on it." |
| "Lumbridge, then. Humour me." | "Lumbridge, then. I want to check something in my notes." |
| "Then take me to the Grand Exchange. I want to watch the crowd for a bit." | "The Grand Exchange, please. Prices change and mine are out of date." |
| "The Grand Exchange, please. There's always something happening." | "Take me to the Exchange? I want to hear what things cost." |
| "Then the Fishing Guild. It's quiet there and I like quiet." | "Then the Fishing Guild. Nothing to record there. That's rather the point." |
| "The Fishing Guild, if you're offering. I'd like to see the water." | *unchanged — a scribe is allowed to just like water* |

**The day summary reads back as notes** (framing only; the assembly logic is
untouched): `"So far: "` → `"Today's page: "`, and the empty case became
"Today's page is blank. Restful, I call that."

**The nickname is what it has filed you under:**

| Was | Now |
|---|---|
| "I've started thinking of you as {nickname}. It fits." | "I've got you filed under {nickname}. It fits." |
| "You know what they'd call you, if they'd watched what I've watched? {nickname}." | "If anyone reads these notes, they'll know you as {nickname}." |
| "All right, {nickname}." | *unchanged — already right* |

**Kept deliberately**, because they read *better* for a scribe than they did for
a construct:

> "You gave me one. I'd have chosen differently, but I wasn't asked."
> "You dress me, I walk behind you, and I keep quiet about your bank."
> "Tired? I once watched you stand at a furnace for three hours."
> "Otherwise I'll find something to look at. I don't need watching every minute."
> "Now and then I wander off on an errand of my own. I always come back."
> "If it's pockets, I give you room and keep an eye on the street."

**One test moved.** `TalkScriptTest.theFollowerTalksAboutWhatItCanActuallyDo`
asserted the script mentions counting by probing for the literal word "count",
which the rewording removed while leaving the capability fully described. It now
matches on the player's question — "You keep track of things?" — because the
answer is characterisation and gets reworded, while the question is the
capability and does not.

**Not changed:** the node ids (`who-me-q`, `who-me-a`) still carry their old
names. They are internal plumbing, not player-visible, and renaming them was
outside the scope of this pass.

Everything else stands. The dry, counting, understated voice needed no change at
all; a scribe explains the counting better than a construct did.
