# The voice

Half a page, as promised in R28. Everything the follower says is written by
this. The lints enforce the mechanical half; this is the half that needs a
writer to hold it.

**Dry, understated, British.** The joke is never announced and never
explained. "Filed under things we don't mention." explains nothing, and
that is why it works. If a line needs a second sentence to land, the first
sentence was wrong.

**One thought per line.** A line does one thing: notices, or teases, or
concedes, or asks. "Restocked. The economy thanks you." is a notice and a
tease in eight words because the second rides inside the first, not after
it. Never two sentences where one will do; never three ever.

**Contract everything.** "I will record it" is a stranger; "I'll write it
down" is company. The ONE exception is deliberate: the days 0-2 era rules
(login-greeting-new, level-up-new) refuse contractions on purpose, because
uncontracted grammar IS how formality sounds. Do not "fix" them, and do not
let their register leak anywhere else.

**One remembered fact per line, framed warm** (this is R21). A single
recalled detail reads as attentive; two stacked read as a dossier. Prefer
the warmth of a number to the number: "the column grows" beats "that is
kill number 214". The count can appear - "{count}. I've been keeping
track" - but it appears alone.

**Claim only what the follower can see** (R7's rule of thumb). It watches
the screen and keeps notes; it does not know drop rates, lore it never
witnessed, or what you did offline. Questions are always safe - they claim
nothing - which is half of why the wondering lines exist.

**The notebook is the character.** Everything routes through the scribe:
deaths are entries, places are pages, the bank is an archive rival. When
stuck for a line, ask what a slightly self-important field scribe would
write, then cut the first half of it.

**Mechanics, enforced by lints, listed here so the writer knows why:**
ASCII only (bitmap fonts index by `charAt & 0xFF`); 85 characters or fewer
(five seconds at reading speed); no em-dashes ever; no line shared between
two rules; overhead pages under 110 for the dialog box. Spoken quirks are
welcome; typography is not where the character lives.

**What the voice is not:** never cruel about the player, only about the
world and itself; never enthusiastic in more than one word ("PET!" earns
its capitals once); never apologetic; never aware it is a plugin.

The caps rule is about enthusiasm. Pain may shout ("THE FIRE IS WINNING!"
is a follower on fire, not an excited one) and a quoted war-cry keeps its
capitals ("FOR THE GLORY OF BANDOS! ...Sorry. It's catching." - and that
"sorry" is the joke, not an apology). The register breaking IS the gag in
both; the rule exists so it never breaks by accident.

## The quirks, kept on purpose (R22)

The uncanny-valley research argues FOR deliberate minor flaws rather than
smoothing them out, and over-adaptation measurably reduces perceived
authenticity. So these are features. A future editing pass that "fixes" any
of them makes the follower more articulate and less alive:

- **It gets things slightly wrong.** The scarecrow that "moved" and did
  not, the ledger it concedes while keeping the grudge, the count it kept
  and wishes it had not. Wrongness in the follower's own head is character;
  only wrongness about what the PLAYER can verify (R7) is a bug.
- **It contradicts itself across time.** "I do like it here. I don't know
  why" and, weeks later, "the file on this place is thick" - both true, both
  said. A companion with a fixed position on everything is a FAQ.
- **The formal era stays formal.** Days 0-2 uncontracted, on purpose,
  forever. Do not modernise it.
- **Two places it cannot be argued out of.** The core tastes (R23) hold
  against any amount of evidence, and the defence lines exist precisely to
  make that stubbornness audible. Never let the ledger win there.
- **It stays a bit self-important.** The notebook matters more to it than
  it should; the crate deserves a study; the anvil "wants recording". The
  gap between how seriously it takes the scribing and how little the world
  needs it is where most of the comedy lives. Do not let it become humble.
- **It goes quiet in odd places.** The trail-off, the low-mood silence, the
  empty-chair damper: a follower that occasionally has nothing to say reads
  as choosing not to speak, and that choice is worth more than any line
  that would have filled it.

The test for a proposed "improvement": does it make the follower more
correct, or more predictable? Only the first is welcome.

## On the page (the chronicle)

The chronicle is this voice written down. The scribe is fascinated and never
says so: the fascination is in what it looks at, how long it stays, and what
it bothers to write. The full rules, with the research behind them, examples
and checks, are in [chronicle-voice.md](chronicle-voice.md). The short form:

1. **The wrong thing, watched.** Build each creature and place page round one
   true thing the scribe saw for itself, usually not the thing an adventurer
   would look at.
2. **Write to the moment the page opens.** Say only what is true at the
   earliest unlock; a quest that has only begun has not happened yet.
3. **Seen, told, read, and by whom.** State what it saw, credit what it was
   told, and credit history to a named source in the world. Never hedge a fact
   the adventurer acts on.
4. **Understatement does the marvelling.** No adjectives of wonder, no "!".
   One sentence in the whole book where words fail.
5. **Doubt once, correct inside the visit.** One doubt a page, about motives or
   impressions, never numbers. A "Correction:" is stated, not apologised for.
6. **Leave one gap, mostly unasked.** One thing it can't account for, stated
   flat or as "To ask:". At most one question a page.
7. **One line about you, and it drifts.** At most one line a page about the
   adventurer, knowing only what the unlock guarantees. Early pages know
   nothing of you yet.
8. **The notebook shows its making.** Underlined, crossed out, a space left:
   once or twice a page, rotated, and never promised without being delivered.
9. **Tell it once, point after.** Every story has one home page. Elsewhere it
   gets one sentence and a pointer, and dates agree.
10. **What happened to us, not how to do it.** Events and reactions in the
    prose; directions and numbers in the lists. Fights are watched, not
    advised.
11. **Measure it yourself, in Gielinor's units.** One number it made itself,
    method showing. Size in words, since the plate can't show it. Never drop
    odds "by my tallies".
12. **Open on a voice, not a verdict.** The summary starts on something said,
    seen or noticed, and classifies second.
13. **Squeamish, and would deny it.** Fights from their edges. No blow lands
    in the scribe's sight.
14. **Fit the window.** Summary about 220 characters, paragraphs 150 to 270
    (360 at most), one long section a page at most.
15. **Plates carry shape, never scale.** A caption is the subject plus one
    thing the picture can't show. Never a bare label, never used twice.
16. **Musts and a rotation.** Rules 1 to 3 and 14 on every page; three or four
    of the rest, rotated, none on more than a third of pages.

The shape of a page, in five lines:

- Summary: two or three sentences, about 220 characters, opener first.
- Creature or place: What it is, Where it is found, Worth knowing; 1,300 to
  1,900 characters.
- Quest: What happened, Who was there, Where it took us, Worth knowing; about
  4,000 characters, 5,000 at most.
- Lists are the ledger: every number and route fact lives there, one per line.
- Plates: two to four a page, the subject first, every later plate named once
  in the prose.
