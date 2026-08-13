# Testing notes

Written 2026-08-11, after five rounds against the systems added that week: the
speech director, the shuffle bag, the corpus-wide recently-said window, the
one-time lines and the arrival arc.

This is not a list of what is tested. It is the handful of things that were
expensive to find out and would be expensive to find out again. Where a number
appears, it is measured.

---

## 1. The bugs were between the features, not inside them

Two real defects came out of the week, and neither was a fault in the feature it
lived in.

**The settling damper could swallow the Wilderness warning.** `SpeechDirector`
exempts an occasion from the relax period. The settling-in damper was added in
the same commit, four lines below, and did not. `enter-wilderness` is group
`area`, so it counts as scenery; it is an occasion precisely because it must
reach a player who has never seen the place; and the follower is only settling
in *because* the player is new. The two conditions peak at the same moment. A
new player walking into the Wilderness within twelve seconds of any other area
line would have been told nothing.

**The speech queue could be switched off with a slider.** `SPEECH_STALE_MS` and
`MAX_SPEECH_MS` were independent constants that had nothing to do with each
other, and both happened to be twelve seconds. Reading-time scaling made the
display duration reach its cap, at which point a line queued behind a
maximum-length one aged out fractionally before the floor it was waiting for
ever freed. It was never spoken. The minimum-display-time setting offered
fifteen seconds and clamped to twelve, so turning that slider up was sufficient
to stop the queue delivering anything, ever — and two rules firing at once is
the entire case the queue exists for.

Neither would have been found by reviewing either feature alone. Both sat under
595 passing tests.

**The practice this argues for:** when several systems land close together, spend
a round on the pairs rather than on the parts. Every one of these systems
intercepts the same moment — a rule has won and is about to speak — and the
order they run in is load-bearing in a way none of their own tests can see. The
inventory of that moment is worth writing down before testing it:

> mute → held floor → director (relax, settling) → speech gap → delay queue →
> shuffle bag → corpus window → reading time → speech queue

## 2. Defence in depth hides its own failures

The corpus window covers for the shuffle bag. `recentLineSet` survives a world
hop — only a full `reset()` clears it — and `pickPhrase` prefers what has not
been heard lately. So when a mutation made a world hop re-deal every bag, the
window quietly produced the same remaining lines a preserved bag would have, and
the regression was **unobservable at engine level**.

This is good for players and bad for verification. Two overlapping safety nets
mean neither can be tested through the other, and a regression in one can sit
indefinitely looking healthy because the other is carrying it.

The test now fires twelve lines from a second rule between the phases — exactly
the window size — so nothing the rule under test said is still remembered
corpus-wide and the bag is the only thing left deciding. **Anywhere two systems
overlap deliberately, a test that isolates one of them has to disarm the other
on purpose.**

## 3. Prove the silence

Three separate false negatives this week, all the same shape at different
levels.

The mutation harness reported **13 of 13 mutations survived** — an implausible
result taken at face value would have read as "the test suite is worthless".
The subprocess could not find `gradlew.bat`, produced no recognisable output,
and the verdict logic read "no failures" as success. It now treats a run with no
`BUILD SUCCESSFUL` as invalid rather than as a pass.

A test asserted only a property the shuffle bag already guaranteed, so nothing
tested that the engine feeds the corpus window at all. The mutation that stopped
it feeding entirely was a **no-op against the whole suite**.

A test ran its scenario once, where a re-dealt bag passes by luck one time in
twenty. It got lucky.

**A test that passes tells you nothing until you have watched it fail.** Every
lint added since is verified by reintroducing the defect and confirming it goes
red — see `aContractionMayHelpAVerbButMayNotBeOne` and
`OccasionGuaranteeTest`.

## 4. Assert the promise, not the method that keeps it

The occasion guarantee was broken within hours of being written, by a second
damper that did not know what the first one knew. Re-reading `blocks()` more
carefully is no defence against that, because the failure was structural rather
than local.

`OccasionGuaranteeTest` asserts the property instead: every shipped occasion,
against every state the director can be in — new follower and old, resting and
listening, settling damper armed. A third damper added later has to keep the
promise without anyone remembering to ask it to.

The same shape applies to `SpeechQueueTimingTest`, which asserts the
*relationship* between the stale window and the display cap rather than either
number, so they cannot drift back into collision.

## 5. Three of five weak spots were in tests written that same afternoon

Not in old code. In tests written, read, and believed within the hour.

- One ran its scenario once against a one-in-twenty coin toss.
- One built its recent-set as `"l1"`, `"l4"` against lines the helper actually
  names `"line 1"`, `"line 4"`. It matched nothing, no skip ever ran, and the
  test exercised none of the code it was named after.
- One asserted a property another system already provided.

Fresh tests deserve the same suspicion as fresh code, and get less of it because
writing one feels like the verification step rather than another thing needing
verification.

## 6. A correction, kept on the record

An earlier commit message and two reports claimed a shuffle-bag corruption bug
had been introduced and fixed — that an overwrite "dropped one line out of the
bag and left another in twice".

**There was no such bug.** At that point the drawn index and `bag[drawnAt]` hold
the same value, and the slot is below `bagPos`, never read again before the
array is rebuilt. The assignment is a dead store; removing it changes nothing,
which is why that mutation correctly survives and always will.

A test did genuinely fail, and the diagnosis of *that* failure — a mistaken
assertion about bag semantics — was right. The error was attaching a second,
invented cause to it and then "fixing" unreachable code. The assignment is kept
because it holds the array to a true permutation at every instant, which is the
invariant worth reasoning about; the comment now says that rather than claiming
to prevent a fault.

Worth weighing whenever a bug is reported *fixed* rather than *found*.

---

## What is still unverified

Everything about the director's tuning in real play.

The soak numbers — 256 lines an hour, the distribution of rest periods — come
from an artificial event stream that fires something on twenty percent of ticks.
That is far denser than play. The only real measurement is the transcript from
before the director existed.

The open question is not answered by any of this work: **whether thirty to
forty-five seconds of quiet after three lines reads as a companion choosing not
to speak, or as the plugin having died.** Only a session and
`tools/transcript.py` answer that.

The baseline to beat, from the whole-day transcript of 2026-08-10:

| | Before |
|---|---|
| Lines repeated within a dozen | 38 |
| `idle-chatter` repeat distance | 8 apart, with 30 variants written |
| `souvenir-mention` firings | 26 |
| Speech by group | idle 39%, reactions 17%, errand 12% |

## Cost, measured 2026-08-11

| | Measured | Budget |
|---|---|---|
| Full rule pass, 371 rules | 88 µs | 1 ms |
| Rules and phrases in memory | 0.5 MB | 2 MB |

The per-rule shuffle bags and the twelve-entry corpus window cost nothing worth
reporting.

## Deep pass over the two-day feature batch, 2026-08-12

Thirteen features shipped in two days (the low-mood arc, delivery-latched
openers, the name prompt, explore, wear, eras, the memory surface, the grind
guard, core tastes, R4 variants, boundaries, stay life, idle contexts), then
the same four-round treatment the first batch got.

**Round 1, interaction mapping, found one real bug.** In a core-liked region
(R23) with the earned score at -80, `place-earned-dislike` said "I hate it
here" in the one place the follower cannot be argued out of liking - while
`defended-like` said the opposite moments later. The earned verdicts claim a
FEELING, so they now require `feelsAbout` agreement, which is a no-op
everywhere except core regions, where the core owns the answer. Pinned by
`theEarnedVerdictNeverContradictsTheCore`.

**The grind guard, measured rather than believed:** `repeating` needs a
hundred sustained ticks to count and resets within four quiet ticks. So the
guard bites during a live grind - the long fight is the case that matters,
since idle-gated openers cannot fire during anything animated anyway - and
releases almost immediately after. The R24 commit's claim that it covers the
bank pause mid-grind was wrong: the counter has reset long before the pause
matters. Acceptable, because a bank pause is a menu rather than the calm the
research protects, but the record should say what the guard actually does.
`GrindGuardTest` now pins both the bite and the fast release.

**Cleared suspicions:** relogging cannot double-merge the wear ledger
(LOGIN_SCREEN resets the engine before the next restore); the boundary kind
is a single slot where the latest ending wins, so a reunion can displace a
pending bank breather within one six-second window, at the cost of one
30%-chance remark - accepted. Noted in passing, pre-existing: the logout path
writes no final counter snapshot, so up to a minute of tallies can be lost
per logout, bounded by the periodic write.

**Round 3 soak:** all the new systems driven at once for 3h20m simulated -
stays toggling, reunions, wilderness stints, boundaries from every ending,
level-ups, deaths - asserting the failure only hours can produce: no rule
disabled by a throwing condition, the wear ledger bounded, no line twice
running, and the follower still capable of speech at the end.

**Round 4 mutations, four for four:** the wear filter removed from the bag
refill, the boundary noted before the evaluation loop instead of after, the
core-taste check deleted from feelsAbout, and the wish latched at hand-off
instead of delivery - each killed by the named test that owns it.

## Second pass, same day: the gaps and the survivor

The first pass tested what the features promise; this one tested what the
TESTS promise. Three gaps filled: the boundary window now has an expiry test
(a twelve-second-old ending opens nothing), the arrival watch has a
lost-to-a-fight test (a look forfeited to combat does not burn the cooldown,
so the next region still gets its curiosity), and the wear ledger has a
long-run property (start one line nearly worn, accrue realistically for two
hundred draws, and the last forty must deal all four lines evenly -
retirement redistributes, it never silences).

**One mutation survived, which is the whole point of running them.** Removing
the settle re-arm from the arrival watch passed the arrival test unchanged:
the test moved the player too briefly for "re-arms on movement" and "merely
keeps counting" to differ. Rewritten as stop-start travel - never eight
quiet ticks in a row, far more than eight in total - the mutant dies and the
watch is actually pinned to CONSECUTIVE stillness. Five other fresh
mutations (arrival cooldown never charged, wear trim dropping the heaviest,
the name cap removed, the boundary window never closing, the greeting eras
overlapping) were killed first try, the last one proving the tiling lint
bites on data as well as code.
