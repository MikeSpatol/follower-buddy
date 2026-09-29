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

## Third pass: the corners the harness cannot see from

**The dialog structural walkers only walked one script.** Every check -
branches lead somewhere, every node reachable, glyphs, page length, always
escapable - ran against the even-band wish-held variant. A dead end that
only existed in the low-band script would go unmet until somebody's bad
day, which is the worst day to have the box shut in their face. All five
walkers now iterate the six structurally distinct builds (two question
shapes crossed with three wish states); all six came back clean.

**The random-ruleset fuzzer predated the new conditions.** Its pool now
carries boundary (bare and kind-specific), staying, inWilderness,
rolledFeeling, daysKnown windows and the none(repeating) guard, and its
driver flips the states behind them mid-flight. Forty seeds, promises kept.

**One glue bug from reading the plugin code:** forgetEverything reset the
engine but left the plugin speech queue holding pre-forget lines, whose
delivery latches would then run against the blank context - a queued wish
ask handing the brand-new stranger an open wish nobody heard asked. The
queue is now cleared with the rest. (Same review confirmed the stay watch
is null-guarded and the bank watch dies harmlessly at logout.)

**Cost re-measured at 424 rules** (was 371 when last recorded): 115 us for
the heaviest pass with sixty NPCs in scene, 21-23 us warm on the tick
heartbeat - the wear lookups, the boundary countdown and the wilderness
refresh cost nothing visible against the 1 ms budget.

## Fourth pass, run by the strategy doc, 2026-08-13

The trigger table said a corpus change owes a dated talk-model re-measure,
and the mutation layer owed the seams the earlier passes skipped. Both paid.

**Two more weak tests found by mutation, both strengthened.** Dropping
"down" from the lowish gate survived, because only "low" and "good" were
ever tested - the sharpened question's coverage of the whole bottom band
now has its own assertion. And latching the QUESTION at hand-off instead of
delivery survived `SwallowedLineTest`, because its three back-to-back
dispatches paced each other out: only the wisher ever actually fired, so
the asks and want halves were asserting quiet about rules that never got
the chance to latch. The openers are now forced individually, and the
deferred-delivery test proves all three open at the landing. Re-applied,
both mutants die; four others (the want latch, the explore pose release,
a stripped grind guard, the comfort lift zeroed) died first try. Twelve
of the campaign's sixteen mutations died on first contact; all four
survivors were real test weaknesses, now fixed.

**The talk-model baseline at 424 rules** (soak driver, three seeds, ten
simulated hours - a stress mix far denser than real play, so compare
against this driver, not against transcripts): 256 lines/hour under that
pressure; **repeats within a dozen: 4 in 2,561 lines** (the 2026-08-10
real-play figure, pre-bag pre-wear, was 38); idle-chatter mean repeat
distance 17.3 over 42 firings of its 38 variants. Speech by group under
the driver: misc 28%, combat 22%, reactions 21%, memory 20% (the driver
dies every twenty seconds; real play will not), idle 5%, bet 2%.

## Fifth pass: the layer only real play can run, 2026-08-13

The strategy's Layer 5 finally got its data: a four-hour real-play
transcript from the day after the batch shipped, digested against the
strategy's questions.

**The find: the wander read as a Stay.** stay-start fired thirty times and
stay-fidget twenty across the afternoon - across four regions, minutes
apart, right after errands - because the idle wander drifts via the same
stayAt as everything else, and the stayCommanded computation excluded
errands, spectating and thrall work but not the wander. "Right. This spot
is mine now", announced by a follower in the middle of its own pottering.
No simulation could have caught it: the harness has no wander. The
discriminator is the plugin's own wandered flag; the computation is now the
stayIsPlayerCommanded function with each exclusion pinned by name.

**Everything else in the transcript reads healthy.** 94 lines an hour
through an active testing session; repeats within a dozen: ZERO in 393
lines, with the bag, window and wear ledger all live - the 2026-08-10
baseline was 38. The context pools served correctly (idle-chatter-town in
the crowded bank, default elsewhere), the bank breather landed sixteen
times in a heavy banking session, the director held back stay-watch and
recall-idle rather than occasions, and the longest silence was six minutes
against the trail-off design's intent.

## Sixth pass: the sequel to the wander find

Three sweeps, one code change. The wander gate is now an extracted,
named function with each of its edges pinned - the 48-second threshold a
play report set, the hand-over to rest, the thieving override, busy always
winning - so the subsystem that just produced the stay bug has a contract
where it used to have inline glue.

A cooldown-violation scare in yesterday morning's transcript (idle-chatter
every two minutes against a five-minute cooldown) resolved against git
history: that session ran the morning build, before the evening frequency
tweak raised the cooldown from two minutes to five. The digest plus the
commit log answered it in two minutes, which is the pairing the strategy
prescribes.

The adversarial-persistence sweep found the ground already held:
corrupt counter blobs fall back rather than throw, a broken phrases.json
mid-session keeps the working rules and reports why, and a repaired file
is noticed rather than remembered as already-read. No gaps.

## Seventh pass: the release campaign, 2026-09-27

Everything since 1.4.0 (c82e360), about sixty commits in seven batches: the
boat and overhead fixes, the settings and panel regroup, skilling company,
dialogue and cutscene manners, the reactions batch (milestones, news sizing,
gaze and noticing, the will, emotes, the earned name, walk-beside,
incidents), Thrall only, and the chronicle. Run by the trigger table's
"before a Hub release" row, all four layers at once.

**The fuzzer had stopped looking.** Its pool was about eighty condition types
behind, and it never checked for a rule disabled by a throwing condition at
all: making `grateful` throw left all seven soak tests green. The pool now
carries every type in `KNOWN_TYPES`, a lint fails when one is missing, every
pool entry also runs as a rule of its own so a throw is caught whether or not
a random rule drew it, and the run fails naming any entry the driver never
made true. That last check found three entries that had never once been
true since they were added (`itemEquipped`, `rolledFeeling`, `daysKnown`),
which the notes above had said the driver flipped. It did not. A sweep made
each new type throw in turn: all twenty were caught.

**The soak had stopped levelling.** Since the milestone work, `levelUp`
needs a level of at least its minimum, and the soak's level-ups carried
none, so three of the four soaks never heard a level line and the talk-rate
driver lost a quarter of its output without anything going red. Found by
re-measuring, not by a test; the driver now carries a level and the soak
asserts the level lines fire.

**Mutations: 58 across the seven batches, 22 killed on first contact, 35
survivors, 1 equivalent.** Every survivor was a weak test, and each now dies
to a new or tightened one. The survivors cluster where the harness never
reached: most of the reactions batch's new behaviour lives in the plugin
glue (the dialogue hush, the nickname answer, what counts as a thrall's
business), and those decisions are now reached directly in
`DialogueHushTest` and `ThrallOnlyTest`. `NicknameTest` had been testing a
hand-written copy of `answerQuestion` rather than the method.

**The interaction map found the release's worst bugs, and they were all
between features.** A player's Stay did not hold: the idle wander, the
spectator, the moments, Noticing and a skilling seat could each take the
feet of a parked follower and then release it into following, while Follow
me stayed unticked. The panel's Stay and Follow did nothing while the wander
or a seat held the feet. Errands, the sketch and the minute-taker took the
hand from a tool Borrow still believed it held. Rest let go a tick late and
wiped whatever pose had just been put on. The glue read-through found the
worst single one: toggling the plugin at the login screen saved a blank
memory over the real one.

**What was fixed.** The readers and the mutation pass turned up twenty-six
findings; a skeptic tried to refute each before anything was changed.
Twenty-four were real. Two were not: the level-base milestone's supposed
zero-level Overall is not in the pinned API's list of skills, and a cutscene
NPC facing the player within five ticks of spawning could not be shown to
happen. Each fix went round the same loop - a test that fails for the bug,
the smallest fix, the fix reverted to watch the test go red - and then an
adversarial review of the fix itself, which found sixteen more problems in
the fixes, most of them tests that did not reach the real path. Then the
five fix branches were merged and reviewed again together, which found
seven more between them, all fixed: among them the doubled tallies the lifecycle
fixer had only suspected (a profile switch at the login screen read the
memory, the login read it again, and the tallies add), a gift the new
landing guard held back for good behind a queued souvenir line, and a park
still pending when the scene went, applied to the next spawn.

Two rules came out of it worth keeping. A player's park (right-click Stay, a
Send, Follow me unticked) is a latched fact, `parkedByPlayer`, that every
system reading the feet checks - not something inferred each tick from
whether the follower happens to be staying, which is what let the wander's
own stay pass for the player's. And a line's latches split cleanly: what a
line leaves behind waits for it to land, except the gift, whose exchange is
confirmed in the Talk-to box and is spent at the win. The first fix moved
the gift too, and reopened the parked-wish bug 8b87f6d had closed; the
fix's own review flagged it, from that commit's message.

**Cost and talk rate at 508 rules**, measured the way the fourth pass did
(the soak driver, three seeds; its record fits 3 x 20,000 ticks, ten hours
in all). Before the level fix: 196 lines an hour against 256, all of it the
lost level lines, with repeats within a dozen at 0 to 2 per 2,000 lines.
With the level carried: 250 lines an hour, 4 repeats in 7,508.
Idle-chatter deals all 38 of its variants, mean repeat distance 25 to 36.
DispatchCostTest's cold pass is 120 to 146 us, the same as the old tree on
the same machine (it is the JIT, not the rules); the warm pass is 28 to 34
us against 21 to 24, about 60 to 70 ns a rule, still under 4% of the
millisecond budget.

**Name lists checked against the cache.** `runTargetAudit` now also reads
the plugin's own lists (the superior slayer names, the guard and bank
names, the landmarks) from the source and matches them the way each call
site does. It found the superior mutated Bloodveld and the Vitreous warped
Jelly spelled as the wiki spells them, not the game, bank booths the game
capitalises (the bank errand had its own copy of the same miss), and forty
superiors the cache files that the list did not know: eleven newer ones and
the Bloodthirsty set, one of them spelled "Bloodthirst rockslug" by the game.
Three landmarks (Noticeboard, Portrait, Sundial) matched nothing the scan can
see and are dropped.

## Eighth pass: the upgrade from 1.4.0, played, 2026-09-28

The first play session on the release checklist installed 1.4.0 fresh on a
new profile (from the 1.13 hotfix branch, since c82e360 no longer compiles),
edited two rules in Messages (one line deleted from the axe rule, one harpoon
line rewritten), then started the release build on the same files.

**The find: the upgrade put a deleted line back.** The three-way merge needs
a record of the defaults as they last stood, and only a merge ever wrote one:
a fresh install copied the starter file and recorded nothing, so its first
merge had no base and guessed, and a rule holding only shipped lines looked
untouched and was replaced by the new default. The rewritten harpoon rule
survived only because it held a line of the player's own. In 1.4.0 that first
merge is simply the second start, so every 1.4.0 player who deleted a line in
their first session already got it back then; the play test met it at the
upgrade only because its install had started once.

**The fix.** A fresh install records what it wrote, for the rules and the
conversation trees alike, so from this version there is no first merge to
guess on. A file that still has no record (an install started once) is judged
against the defaults 1.4.0 shipped, bundled under `seen/`, but only when it
holds every rule 1.4.0 shipped, and only for the rules that match 1.4.0 apart
from their lines: the adversarial review ran the merge on every released file
and found that judging a 1.2.0 or 1.3.0 file against 1.4.0 would have taken
their untouched rules for edited ones, and kept back 99 lines and the boss
send-off fix for good. With the narrowing, an untouched 1.1.0 or 1.2.0 file
merges as before, a 1.3.0 file comes out exactly the current defaults, and the
play test's own 1.4.0 files keep the deleted axe line and the harpoon edit.
The guess still leaves two recall rules a line behind on the oldest files, as
it always did. ThreeWayMergeTest and DialogMergeTest build their no-record
cases from the real 1.4.0 files; mutating either condition of the narrowing
fails a test.

**Also found in play, 29 Sep.** Profile switches put one profile's outfit on
another: the named outfits were one list shared by every RuneLite profile,
every change saved into the selected name and a switch reloaded it (issue
#1's other half); each profile now keeps its own list. And opening the logout
tab never waved: the panel is loaded once, at login, and a tab press only
shows it, so the load the wave listened for never came with the press; it is
now watched as the panel coming into view. Neither had been seen in play
before; the tests drove the pieces directly. A Stay's tile is now also
remembered across a relog or hop, asked for after the Stay check.
