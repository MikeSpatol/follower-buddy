# Testing strategy

What to run, when to run it, and why each layer exists. This is not a
generic pyramid: it is built from where this plugin's bugs have actually
come from, which is a record [testing-notes.md](testing-notes.md) keeps in
detail. The short version of that record: bugs live **between features**
(core tastes against the earned verdicts, the speech queue against the wish
latch), **in the plugin glue** the harness cannot reach (the spawn path the
name prompt armed on, the queue the eraser forgot), **in the data** (rules
that load and never fire, scene names that match nothing, silently), and
**in feel** (the first gift design, the idle over-talking) - almost never
inside a single well-tested class.

## Layer 0 - Lints as law

*Runs on every build; lives mostly in `RuleSetIntegrityTest`.*

The highest-value pattern in the codebase. When a bug is fixed or a
convention adopted, it is encoded as a structural check over the whole rule
set, so it cannot quietly regress: placeholder supply derived from
conditions, study/explore targets tied to their lines, the voice eras tiling
the calendar, the mood and grind guards present on every rule that needs
them, scene scans ordered after the chance that usually discards them,
ASCII-only under 85 characters, no two rules sharing a sentence.

**The rule: no fix ships as a habit; it ships as a lint.** A lint is the
only reviewer that reads all four-hundred-odd rules every time. Prefer
field-driven lints (anything carrying `asks` must consult `repeating`) over
id-driven ones (these three rules must), so a future rule cannot arrive
outside the law.

## Layer 1 - Behavioral tests, with the house conventions

*Runs on every build.*

Three conventions are requirements, not suggestions, because each one was
bought with a shipped bug:

- **Every condition type** has a case in `EveryConditionTypeTest` AND a call
  in its coverage sequence AND an entry in `KNOWN_TYPES`. The coverage
  assertion is what notices when one of the three is forgotten.
- **Anything wall-clock takes the injectable clock.** A simulation runs ten
  thousand ticks a second; real windows make every timing test a lie.
- **Anything that latches state decides explicitly whether it belongs at
  the win or at the delivery**, and tests the dropped-line path either way.
  The queue can drop what the engine already counted; `SwallowedLineTest`
  is the precedent.

Test the refusals as hard as the happy paths. The errand suite is built
refusals-first - never mid-fight, never in the Wilderness, always released
however it ends - and errands have never produced a play-report bug.

## Layer 2 - Soaks and the fuzzer

*Runs on every build; fake clocks make simulated hours cost seconds.*

`LongSoakTest` drives the real rules for hours with everything live;
`EngineSoakTest` drives random rule sets under random events for the
combinations nobody thought of. Their irreplaceable assertions:

- **No rule disabled by a throwing condition.** A condition that throws
  once silences its rule forever, and the only symptom is a line nobody
  ever hears again. This is the killer for every new condition type.
- Once is once, across every window lining up.
- Bags deal evenly; the wear ledger stays bounded.
- The follower is still capable of speech at the end.

**The checklist item this bought: every new condition type goes into the
fuzzer's pool, and if it is stateful, its state gets flipped in the
driver.** The fuzzer was three features behind before somebody looked.

## Layer 3 - Mutation spot-checks

*Runs once per feature batch, by hand.*

Four to six hand-picked mutations of what the batch actually changed:
filter logic, ordering decisions, boundary constants, persistence merges.
Two rules of discipline, both bought with false verdicts:

- **Silence is invalid, never survived.** A sweep once reported 13/13
  survivors because the subprocess could not find gradlew and "no output"
  read as "no failures". A kill is BUILD FAILED naming the expected test.
- **A survivor is the product, not an embarrassment.** Both survivors so
  far exposed genuinely weak tests - the arrival watch that was never
  pinned to consecutive stillness, the bag window that asserted what the
  bag already guaranteed - and each was strengthened until the mutant died.

Automated mutation testing is not worth it at this scale; the hand-picked
six target exactly the seams the batch opened.

## Layer 4 - Reality checks

*Runs per feature, in the game.*

The no-guessing pillar. Animation, item and object identities come from the
cache, the pinned jar, `sniffanims`, or `::follower errandscan` - never
from memory or the wiki. Two habits keep this layer honest:

- **Every feature ships with a force-path** (`::follower errand explore`,
  `::follower mood 10`, `::follower fire <rule-id>`). A feature that cannot
  be summoned on demand cannot be verified, demonstrated, or debugged.
- Each feature gets one deliberate play session against written test steps
  before it is called done. The restart-and-log loop (kill client, relaunch,
  grep for "Loaded rules") is the cadence.

## Layer 5 - The transcript loop

*Continuous. The only judge of feel.*

Every real bug report so far came from playing, and half were about feel -
too chatty, too soon, wrong tone, ambiguous line - which no other layer can
see. The loop stays as it is: play, report in one sentence, read the
transcript (`python tools/transcript.py`), fix surgically, pin with a test.

One standing measurement: **after any corpus or pacing change, re-run the
talk-rate ranking** (the soak driver, three seeds, counts per rule) **and
date the numbers in testing-notes.md** - lines per hour, repeat distance,
speech by group. That turns "feels chattier lately" into a diff.

## Standing rituals outside the layers

- **Read the plugin glue after each feature batch.** The tick handler is
  the largest surface the harness cannot reach, and both glue bugs of the
  last campaign were found by reading, not running. Counter the pressure by
  extracting decisions into pure functions - `cleanFollowerName`,
  `talkScript`, `MemoryDialog.summarise` all became testable that way.
- **Re-record the cost table** (`DispatchCostTest`) whenever the rule count
  moves about ten percent. The budget is a millisecond a pass; the measured
  figure belongs in testing-notes.md next to a date.

## The trigger table

| When | Do |
|---|---|
| Fix a bug / adopt a convention | Encode it as a lint, same commit |
| New condition type | Case + coverage sequence + `KNOWN_TYPES` + fuzzer pool (+ driver if stateful) |
| New persisted field | `lived()` + roundtrip assertion, same commit |
| New opener, guard, or target list | Field-driven lint, never id-driven |
| Feature batch lands | Interaction-mapping pass + 4-6 mutations + glue read-through |
| Corpus or pacing change | Talk-rate re-measure + one idle and one combat session before push |
| Before a Hub release | Full campaign, fresh transcript baselines, the smoke list below |

## The in-game smoke list

Ten minutes, before any push a stranger might install. Everything here has
a force-path, so none of it waits on luck:

1. Fresh login: the greeting arrives a beat after the spawn, in the era the
   follower's age calls for.
2. `::follower mood 10`, Talk-to: "You all right?" is offered; picking it
   lifts the mood. `::follower mood 80`: the everyday question is back.
3. `::follower errand explore` near a chest or signpost: walk, stare,
   scroll out, verdict line, return.
4. `::follower errand study` and one of bank/altar/fire: trip out and back,
   nothing left posed or held.
5. Right-click Stay, walk out of sight, come back: the post is acknowledged
   and the reunion noticed. Follow releases it.
6. Open a bank, close it, linger: the breather is possible (chance-gated -
   absence is not failure, but a line here is a pass).
7. Side panel, Memory...: the window reads true against the session; the
   incident row reads as a quote with its file and count.
8. Talk-to with a wish open, both branches: the gift with the item held,
   the bluff without; the option disappears after the trade.
9. `::follower transcript` on for the session, off at the end; skim it for
   anything said mid-task that should have waited for a boundary.
10. Toggle the plugin off and on: clean shutdown, clean return, memory
    intact.
