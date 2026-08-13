# What makes a game companion fun — research findings

Compiled 2026-08-10 for Follower Buddy. Five rounds of research, merged.
Sources are listed at the end and cited inline as `[n]`. Where a claim is my
inference rather than something a source says, it is marked **(inference)**.

Part 3 measures the follower against the findings; Part 4 is the prioritised
list of changes. Round 2 additions are marked in the changelog at the end.

---

## Part 0 — The short version

Twenty-six findings, ordered by how much they should change what we build.

1. **Repetition is the number one killer.** Most-cited complaint in every
   source. Industry practice for a frequently-heard callout is 8–20+ variants
   [3]; our median rule has 3, and 99 rules have exactly 2, which the current
   picker turns into perfect A-B-A-B alternation.
2. **Pacing beats volume.** Valve's AI Director does not tune *how much*
   happens, it tunes *when*: build up, peak, then a mandatory 30–45 second
   relax before anything else is allowed [18]. We have per-rule cooldowns and no
   director at all.
3. **A companion has two failure modes, and they are opposite.** It can be a
   *burden* (escort problem) or *noise* (Navi problem). Almost all design advice
   is about staying between them [2][7][8].
4. **The order of work is: never be a burden → be useful → be charming.** Ellie
   "felt great" before she could do anything at all, purely from moving well and
   staying out of the way; utility and character came after [19].
5. **Believability comes from apparent inner life, not fidelity.** The illusion
   of life matters more than rationality or efficiency [13], and the key "does
   not lie in the complexity of the systems driving it but in the nuances of the
   character performance" [19].
6. **The strongest single effect is "the game noticed me".** Hyper-specific
   reactivity to something only this player did [10][11].
7. **A wrong observation costs more than a missing one.** Ellie calling out an
   enemy the player could not then see "reflected badly on Ellie's intelligence";
   the requirements on an AI are stricter than on a person [19].
8. **Interrupt at task boundaries.** Subtask boundaries are the lowest
   cognitive-load moment and the ideal interruption point [20]; urgency should
   change both the *timing* and the *phrasing* [21].
9. **Imperfect obedience creates the impression of a will.** Ueda made Trico
   slow to respond so it read as having its own mind [5].
10. **A companion that only punishes neglect gets resented** [9].
11. **Agreeable companions go flat.** Friction and surprise are what stop a
    companion becoming a mirror [12].
12. **Silence is a design tool, and player-initiated conversation beats
    companion-initiated** [11].
13. **The companion must be significant to story *or* gameplay** [1][14].
14. **Novelty always fades; surprise is what survives.** Habituation is
    automatic and unavoidable [22]. Surprise is not novelty — it is the
    *unpredicted* [22][23]. Variable schedules resist extinction best [23].
15. **We are writing text, not recording voice, and the budget is different.**
    Comfortable reading is ~17 characters per second, hard ceiling ~20, with a
    1.5s floor for anything at all [27][28]. Our display is a fixed 4 seconds,
    and **95 lines cannot be read in it**.
16. **A joke decays faster than an observation, and randomising it costs the
    thing that made it funny.** Games that randomise quips "traded on-point
    reactivity for broader utility and variety" [29]. This qualifies finding 1:
    more variants is right for *observations*, wrong as a blanket rule for
    *jokes*.
17. **Knowing too much is its own failure mode.** A companion that remembers
    everything "may feel attentive, but it may also become invasive" [30], and
    hyper-realism backfires — stylisation and deliberate imperfection sit on the
    safe side of the uncanny line [31]. Anthropomorphism is a tunable parameter,
    "hazardous when left unchecked" [32].
18. **Chattiness should be a player-facing setting.** Forspoken's four-level
    Cuff Chat Frequency was singled out as a feature every game should copy [33].
    We expose a raw milliseconds box instead.
19. **The first session decides whether the plugin stays installed, and ours
    leads with the wrong thing.** Confusion, not boredom, is what kills early
    retention [37][38]. Measured: **day one is 82% world-facts and 18%
    personality**, and 43% of the personality lines are gated behind history the
    player does not have yet. The follower's first impression is a guidebook.
20. **Attachment comes from time the player invested, not from what we built.**
    Psychological ownership follows effort — the IKEA effect — and *cosmetic*
    customisation drives identification more strongly than functional [39][40].
    Critically, **delaying customisation creates a drought where users churn
    before ownership forms** [40]. Ours ships called "Follower" and nothing ever
    asks the player to change that.
21. **Adapting to the player too completely destroys trust.** Experimental
    result: companion chatbots that mimic the user heavily are rated *less*
    authentic and trustworthy; ones that keep their own distinctiveness build
    stronger connection. Optimal design needs deliberate **limits** on
    adaptation, not maximisation [41].
22. **Our core use case is a player seeking calm.** Grinding is frequently
    chosen *because* it is repetitive — a mindful, low-decision state and a
    break from decision fatigue [42]. It is also social: shared grinding turns
    repetition into connection [42]. Ambient company is the brief; demanding
    attention works against the reason the player is there **(inference)**.
23. **Our closest real-world analogue is five times our size.** Inigo — a
    fan-made Skyrim follower, 4.5M+ downloads, singled out by Todd Howard — has
    **7,000+ lines** against our 1,470 [43][44]. And its answer to repetition is
    not machinery: "no specific mechanic prevents repetition — the strategy is
    providing enough unique lines" [44]. A useful counterweight to findings 1–2.
24. **The best-loved companion mod gates content on a hidden friendship stat,
    not on story.** Time together, shared activity and conversation raise it,
    and topics unlock organically as it climbs; the personal arc unfolds over
    20–30 hours [44]. Its idle behaviour asks about *the player's* motivations,
    and left at home it uses the space — sits, crafts, comments on the decor
    [44].
25. **We have never measured what the follower actually says.** Playtesting
    methodology is observation, think-aloud and structured questioning [45][46];
    ours has been the user's impressions plus a model. The plugin keeps one
    `lastSpokenText` and some debug logging that is off by default.
26. **1,470 lines exist with no style guide.** Standard practice is a living
    style document defining voice, plus reading dialogue aloud to check it
    sounds natural [47]. Ours was written across many sessions with the voice
    held only in my head, which is exactly how a corpus drifts.

---

## Part 1 — The evidence

### 1.1 The two failure modes

**The burden.** The escort literature is unanimous that the problem is punishing
the player for the AI's behaviour: companions that walk into fire, block
doorways, move too slowly, or cannot look after themselves [8]. Naughty Dog's
stated fear was that Ellie would turn the game into "a 12-hour exercise in
frustrating and awkward escort missions"; they scrapped her AI five months
before release and rebuilt it in six weeks [7][2].

Their solutions, now available in full from the Game AI Pro chapter [19]:

- **Keep the buddy close.** Two reasons, and the second is the one nobody
  guesses: a buddy far from the player "is easily forgotten, and the player will
  feel alone in the world" [19]. Proximity is a presence mechanism, not just a
  safety one.
- **The attribution principle.** If the buddy shares the player's space and
  behaves similarly, her actions "can by definition be no more stupid than what
  the player is doing" — so if she is spotted, the player would have been
  spotted too, and blames themselves rather than her [19]. This is the single
  most elegant idea in the literature.
- **A follow region** — a torus around the leader, with candidate positions
  generated by three sets of navmesh raycasts: outward from the leader to check
  a clear line of movement, forward from each candidate to reject positions
  facing a wall (a buddy standing against a wall "feels unnatural"), and from
  the player to check visibility [19][2].
- **Enemies cannot see her outside combat** [19][2]. They got buddy stealth
  working 90–95% of the time and *still* chose to break realism, because if she
  gave away the player's position even once "the bond between them would become
  fractured" [19].

The generalisable rule: a companion's first job is to cost the player nothing.
Charm is built on top of that, never instead of it.

**The noise.** Navi is the canonical case, and the specific complaints are
instructive: unskippable dialogue repeating an item description the player just
read; interrupting to nudge the player back toward the plot when they wander
[6]. The same is levelled at Fi in Skyward Sword [6]. Fallout 4 player
discussion converges on the same thing — a companion parked in a settlement
talks about nothing but mirelurks, and the tenth repeat is where irritation
starts [4].

What players say they *don't* mind: dialogue that is spaced out and doesn't
repeat much [4]. The objection is almost never to the existence of chatter.

### 1.2 Repetition, in detail

- Game audio teams typically want **8, 12, sometimes 20+ variations** of a
  single callout so randomisation isn't obvious [3].
- Variants must be genuinely different; letting two drift close together
  "undoes the whole point" [3].
- Layering multiplies cheaply: five variants across three layers gave 125
  combinations, and players never noticed a repeat [15]. The text equivalent is
  composing a line from independently varying parts **(inference)**.
- More variants make the system *more* sensitive to a bad one — an odd sample
  stands out rather than being masked [15].
- Some studios **swap whole bark sets by context** so the pool itself evolves
  [3].
- Hades will not repeat a line until every unused option is exhausted, which
  takes tens of hours [10]; Kasavin's stated solution was simply to give
  characters a great deal to say [10]. It also **gates access** — you cannot
  talk to an NPC again until you next encounter them, so players cannot mine the
  pool dry in one sitting [10].

**Valve's Response System is the shipped reference implementation** [24][25].
Worth copying its vocabulary directly:

- A rule is a set of **criteria**; its score is how many match. Criteria can be
  marked *required* — fail one and the rule can never be chosen — while
  non-required criteria merely boost the score. Highest score wins [24]. This is
  a graded version of our binary condition tree **(inference)**.
- Response groups **use every response before repeating any, by default** [25].
  A shuffle bag is the out-of-the-box behaviour, not an optimisation.
- `permitrepeats` opts out of that; `sequential` plays in listed order; and
  **`norepeat` disables the group entirely once exhausted** [25] — a shipped
  mechanism for one-time content.

### 1.3 What "believable" actually means

Bates and Loyall's requirements: personality that infuses everything the
character does; emotion, both their own and in response to others;
**self-motivation** — internal drives pursued whether or not anyone is
interacting with them; and change over time consistent with the personality
[13]. Bates's central argument is that the illusion of life matters more than
rationality or efficiency [13].

Lankoski and Björk add: sense of self, awareness of surroundings, initiative,
contextual conversational responses, goal-driven development, and **own agenda**
[1].

Bouquet et al.'s design space has seven aspects: appearance, sentience,
individuality, behaviour, communication capabilities, relation to the player,
and significance [1]. Two observations matter most:

- Many well-liked companions operate under "rather simplistic conditions" on
  closer inspection [1]. Perceived depth and actual depth are different things.
- The failure of contextualised response is what gives the game away. Their
  example is Oblivion: talking to an NPC mid-battle has no effect on the
  conversation, and asking the same question always gives the same answer
  because the NPC does not know you already asked [1].

Emmerich et al. (N=237): players attach great importance to a companion's
**personality** and its **integration into the story**, and expect behaviour to
be **context-sensitive** [14].

Naughty Dog's version of the same point, and the most quotable line in the
research: the key to good buddy AI "does not lie in the complexity of the
systems driving it but in the nuances of the character performance" [19]. They
also spent the majority of development on **iteration rather than new systems**,
describing an "almost manic obsession" with fine-tuning follow positioning,
firing parameters and ambient dialogue triggers [19].

They also refused to cheat: even where the player would never notice, cheating
"moves you away from creating a living, breathing character" [19]. Note the
tension with the invisible-to-enemies decision — the resolution is that they
broke realism only where the alternative was to fracture the bond **(inference)**.

### 1.4 Reactivity, and the cost of being wrong

Supergiant's stated goal is reactivity — "moments where players feel the game is
paying attention" [10] — implemented as a bucket of lines filtered by conditions
and weighted by importance and immediacy, in three tiers: evergreen,
conditional, and essential beats that override everything [10]. The example that
shows the mechanism: Hypnos remarks on *how you died* [10]. Specificity is the
whole effect.

Firewatch tracks whether the player picked up a bottle before or after looking
out of a window and plays different lines accordingly; many players never
realise how much dialogue their own actions caused [11].

**The other side of reactivity is the risk of being wrong.** Naughty Dog's
callout system was initially "overzealous": Ellie would announce an enemy she
had merely glimpsed, or one who had ducked back behind cover. When the player
could not then spot the enemy, "it reflected badly on Ellie's intelligence", and
they iterated hard to eliminate bad callouts — noting that the requirements on
an AI here are stricter than they would be for a human [19].

**(inference)** This is the most under-appreciated finding for us. Every
observational line the follower makes is a claim the player will check. A claim
the player cannot verify does not read as a quirk; it reads as the character
being stupid, and it is *worse* than saying nothing.

### 1.5 Pacing: the AI Director model

Valve's Director does not tune how much happens; it tunes when [18]:

- **Survivor Intensity** is a running value. It rises with damage taken,
  incapacitation, being pulled off a ledge, and nearby deaths (inversely
  proportional to distance). It decays toward zero over time — but **does not
  decay while the player is actively engaged** [18].
- The cycle is **Build Up** (full threat until intensity crosses a peak
  threshold) → **Sustain Peak** (3–5 seconds after the peak) → **Peak Fade**
  (let the current engagement play out) → **Relax** (minimal threat for **30–45
  seconds**, or until the players have travelled far enough) → Build Up again
  [18].
- Even the random elements are bounded: mobs at randomised **90–180 second**
  intervals on Normal [18].

**(inference)** The whole model ports to speech. The relax period is the part we
lack entirely: after a burst of lines there should be an enforced quiet spell,
regardless of what any individual rule's cooldown says. It is also the cheapest
way to make occasional lines land, because contrast is what makes them land.

### 1.6 When to interrupt

From the proactive speech agent literature, which is the closest match to what
the follower actually is [21]:

- People interrupt **significantly sooner when the interruption is urgent** [21].
- People **vary phrasing and delivery to reflect urgency**, not just timing [21].
- People balance speed against accuracy, "often using cues from the task they
  interrupted" to choose the moment [21].
- Interruption strategies are highly diverse between individuals; some read the
  other person's task, some apply fixed strategies regardless [21].
- Structuring focuses on **word length, naturalness, clarity, and tone** [21].
- "Access rituals" (forewarning that an interruption is coming) were used by
  some participants but **rarely** [21].

From the flow and cognitive-load side: natural breakpoints such as **subtask
boundaries are frequently the lowest cognitive-load moment** within a task, and
so the ideal place to interrupt [20]. Directions should not be given during
high-stimulation moments [20]. Non-modal, ambient presentation preserves flow
where a modal interruption would break it [20].

**(inference)** Together these argue for a **boundary detector**: prefer to
speak when something has just ended — a fight, a level, an arrival, an
inventory filling — rather than on an idle timer in the middle of a task. Our
`idle` condition is a rough proxy for this and it is not the same thing: a
player at a furnace is idle *and* mid-task.

### 1.7 Imperfect obedience and having an agenda

Ueda on Trico: it is not like the cute pets in other games, not a really useful
ally, and it does not always do what you ask — that ambiguity is one of the
themes [5]. Withholding instant response fosters the impression that the
creature has its own will and heightens the illusion that the companion is more
than a puppet [5].

This aligns with self-motivation in the believable-agent requirements [13] and
"own agenda" in the companion design space [1].

### 1.8 The care loop — and its failure mode

A pet is a separate being that depends on you, which activates nurturing rather
than identification [9]. Named drivers: **dependency** (it doesn't just belong
to you, it needs you), the **Zeigarnik effect** (an unmet need is an open loop
the mind returns to), **reciprocity** (visible response to care, so you *want*
to show up rather than *have* to), and **pet-initiated surprises** [9]. The
recommended shape of consequence is "a pet that suffers but recovers" — stakes
without permanent loss [9].

The explicit warning: **if the pet only punishes neglect, users will eventually
resent it** [9]. Arbitrary links between pet state and player action also feel
unmotivated and reduce investment [9].

### 1.9 Friction, and why agreeable companions die

From the AI-companion world, which has run this experiment at scale: companions
go flat within weeks, and the cause is the removal of friction — users train the
system into a narrow agreeable range until "you have built a system that only
reflects your own thoughts back" [12]. Mirrors aren't interesting conversational
partners [12]. The fixes named are persistent cross-session memory, topic
variety, and tolerating rough exchanges [12].

Emmerich's design space treats **power dynamics** and **obligations** as
first-class: companions who feel indebted, who must be earned, who are hired,
even who have a sinister agenda [1]. Not every companion is simply pleasant.

### 1.10 Silence, and who starts the conversation

Firewatch: Delilah initiates through radio prompts, but the player can respond
*or ignore any prompt*, and non-engagement is meaningful rather than punished —
she becomes less personal, or quiet for stretches [11]. Talking more exposes you
to more dialogue, so engagement is opt-in [11].

**(inference)** Close to an ideal model for a plugin companion: the follower
offers, the player decides, and the relationship reflects the pattern of choices
without ever demanding one.

### 1.11 Significance

A companion can matter to the story without mattering to gameplay, or the
reverse, but to be well perceived it should be **high in at least one** [1][14].

Counterpoint worth noting: The Last of Us companions have no clear gameplay
function, and are sometimes ignored by the game's own systems — read by the
authors as avoiding player frustration [1].

### 1.12 Interaction affordances, idling, and exploring

The "Can You Pet The Dog?" phenomenon is evidence of real appetite: players want
a way to *do something to* the companion, and its absence is noticed [16]. These
marginal interactions make players feel part of the world rather than visitors
in it [16].

Naughty Dog's two cheapest wins were both of this kind [19]:

- **A library of idle animations** — cleaning her knife, tying a shoelace,
  straightening her hair.
- **An "explore" system**: designers instrumented the world with points of
  interest for the buddy to wander to and interact with. It "only took a day or
  so to implement", designers loved it, and it drew a lot of positive feedback —
  "she shared in the player's wonderment at this abandoned world" [19].

And one accident worth knowing about: Ellie's vocalisations frequently *mirrored*
what the player was exclaiming out loud after a bad fight, making "an entirely
unplanned connection" [19].

### 1.13 Long-horizon depletion and habituation

Stardew Valley is the cautionary case: NPCs recycle the same 10–20 phrases, and
once heart events are done the characters become "robotic and repetitive" [4].

The psychology explains why more content alone cannot fix this. Habituation —
decreased response to a repeated stimulus — is automatic and adaptive; brains
prioritise what is new, surprising, or personally relevant, and predictable
stimuli stop registering [22]. Crucially, **surprise is not the same as
novelty**: surprise is what is *not predicted by available cues* [22]. And
variable-ratio schedules produce the most extinction-resistant responding [23];
what matters is when people expect something meaningful and how strongly they
feel its possibility [23].

The commentary on fading novelty adds the constructive half: rather than chasing
novelty, extend engagement by **varying context and presentation**, and by
shifting attention to change *within* the same thing rather than to new things
[26].

**(inference)** For us this means three concrete things: unpredictable *timing*
is worth as much as unpredictable *content*; personally-relevant lines
(built from the player's own history) resist habituation far better than
generic ones; and the follower's own gradual change is a legitimate substitute
for new content.

### 1.14 Text is not voice, and it has a hard budget

Every bark source in Parts 1.1–1.2 assumes recorded speech, which sets its own
pace. Ours is text on screen for a fixed time, and the constraint is reading
speed rather than delivery.

The subtitling standards are consistent and numeric [27][28]:

- **15–20 characters per second**, with **17 CPS the comfortable standard** and
  ~20 the ceiling for practised readers. Past 20, text becomes hard to read.
- Characters per second is preferred to words per minute because word length
  varies; the equivalent comfortable rate is roughly **160–180 wpm** [27].
- A line should stay up for a **minimum of 1.5 seconds** even if it is one word,
  so the brain has time to process it [27].
- For games specifically, staying inside that range is what lets the player read
  the line *and still watch the game* [27].

**(inference)** Two consequences for us. First, a fixed display duration is the
wrong model — duration should be a function of length, floored at 1.5s. Second,
line length is now a measurable quality with a threshold, not a matter of taste,
and it can be linted like the glyph coverage already is.

### 1.15 Comedy decays faster than observation

The general comedy craft applies — build-up then reversal, rule of three,
subversion of a set-up expectation, and timing that puts the funny word as late
as possible [34][35] — but games have a specific problem: "a joke, upon repeated
hearings, is never as funny as it was the first time" and repetition is a core
mechanic [29].

The important finding is that the obvious fix is not free. Games that randomise
their quips "traded on-point reactivity for broader utility and variety" [29] —
you buy tolerance to repetition by giving up the precise timing that made the
joke land. Player agency is what breaks the timing: if the player wanders off
mid-delivery, the rhythm shatters, and taking control away to fix it "slightly
betrays" what games are for [29].

The article's other route is worth more to us: comedy placed in things the
player *finds* — flavour text, an oddly-posed skeleton — protects tonal balance
while rewarding curiosity [29].

**(inference)** So finding 1 needs splitting. An observation ("we've been at this
two hours") tolerates being one of twelve variants. A joke does not — its value
is concentrated in specificity and rarity, and diluting it across variants makes
each one weaker. Jokes should be rarer, more reactive, and retired sooner;
observations should be numerous and evenly shuffled.

### 1.16 Knowing too much

We have spent three rounds adding memory. This is the countervailing evidence.

A companion that remembers everything "may feel attentive, but it may also
become invasive" [30]. The same source notes that accumulated history, inside
jokes and shared references are exactly what create depth — and that this depth
"can cross into uncomfortable territory" [30]. The recommended mitigation is
**transparent memory controls: letting users see and delete what is remembered**,
which defuses the surveillance feeling that invisible personalisation creates
[30].

The uncanny-valley literature for companions makes a related point from a
different angle: pursuing ever more human-like behaviour backfires, and the
triggers are behavioural rather than visual — something "missing or subtly off"
reads as wrong rather than as charming [31]. The recommended posture is
stylisation over realism, and **deliberate minor flaws and quirks** to increase
relatability rather than smoothing them away [31].

And the anthropomorphism research frames it as a dial rather than a goal:
minimal cues are enough to trigger social response at all (the CASA result), so
anthropomorphism is "a tunable design parameter, capable of supporting user
goals when calibrated carefully, but hazardous when left unchecked" [32].
Believability rather than realism is the target, and warmth plus competence
matter more than surface fidelity [32].

**(inference)** Our specific exposure: the follower now knows the date it met
you, what your gear was worth that day, where you have died, which places have
gone badly, and a nickname derived from your worst habit. Each is charming
alone. The failure mode is *aggregate* — a line that stacks several of them, or
one that reports a number about the player they did not want counted, tips from
"it was there" to "it has a file on me." The mitigations are cheap: keep claims
singular, keep them warm, and give the player a way to see and clear what is
stored.

### 1.17 Companions with their own lives

BioWare's stated philosophy for their most recent companions: they are designed
as independent entities with their own arcs, "their own concerns, fears,
distractions, and personal spaces", and the world continues regardless of the
player [36]. The strongest expression of it is that companions form
relationships *with each other*, independent of the player [36].

**(inference)** We have one companion and no cast, so the literal version is
closed to us. The portable part is that the follower should have relationships
with the *world* — particular NPCs it recognises, places it goes, things it
notices — rather than only with the player. That is what §1.12's explore system
buys, and it is the cheapest available route to "it has a life."

### 1.18 The first session

The retention literature is mobile-flavoured and only partly transfers, but two
points do. **Confusion, not boredom, is what kills early retention** [38], and
the opening minutes decide whether a thing stays installed at all [37]. The
standard advice — get to the actual experience within seconds, keep any tutorial
short and skippable, deliver an early win [37][38] — applies to a plugin as much
as to a game, and more sharply: uninstalling a plugin is one click and costs
nothing.

**(inference)** The relevant question is therefore not "is the follower good
after 200 hours", which most of this document addresses, but "what is it like
for the first twenty minutes", which none of it did until now. See G20 for the
measurement; the short version is that a new player meets a guidebook rather
than a character, because almost everything characterful is gated behind history
they have not accumulated yet.

### 1.19 Ownership comes from what the player put in

Customisation research converges on psychological ownership: people form
attachment in proportion to the effort they invested, not the value of the
object — the IKEA effect [39][40]. Two refinements matter for us:

- **Cosmetic customisation drives identification more strongly than functional
  customisation** [39], which is fortunate, since cosmetic is all we are allowed.
- **Delayed access is a named failure mode.** Locking customisation behind
  progression creates "drought periods where users churn before ownership
  forms"; early access is described as essential [40].
- Half-built customisation is worse than none: options without supporting depth
  signal a product that "cares about engagement but will not invest in it" [40].

**(inference)** We have the customisation — outfits, colours, name, saved
profiles — and it is genuinely deep. What we do not have is any moment that
*invites* the player to use it. It sits in a config section and a side panel,
and the follower ships called "Follower". The single cheapest attachment win
available is to make dressing and naming it part of the first few minutes.

### 1.20 Do not adapt all the way

Round 2 took "agreeable companions go flat" from a blog post [12]. There is now
an experimental result behind it. In a two-condition study of companion chatbots
with linguistic-mimicry analysis, **heavy adaptation to the user reduced
perceived authenticity and trust**, while companions that kept their own
linguistic distinctiveness produced stronger connection [41]. The mechanism
proposed is that over-adaptation reads as calculated rather than genuine, and
that maintained distinctiveness is taken as evidence of actual personality and
independent judgment [41]. The authors' conclusion is explicit: optimal design
requires intentional *limits* on adaptation rather than maximising it [41].

**(inference)** This lands directly on something we shipped last round. Earned
taste currently *overrides* the rolled taste completely once a region's score
passes the threshold — the follower's opinions converge on the player's history.
The research says keep a core the player cannot move: a few places it likes or
dislikes for no reason it will give, immune to experience. That is the
difference between a companion with taste and a companion that agrees with you.

### 1.21 What the player is actually doing

Worth stating because it reframes the brief. Grinding in an MMO is often chosen
*because* it is repetitive: the routine acts as a form of mindfulness and a
break from decision fatigue, producing a calm, flow-adjacent state [42]. It is
simultaneously social — shared grinding sessions turn repetition into
connection, with players chatting for hours over a routine task [42].

**(inference)** Both halves matter. The second is the justification for the whole
plugin: company is what makes a grind bearable, and the follower is company for
players who are grinding alone. The first is a constraint on how that company
should behave — someone who has deliberately entered a low-demand state does not
want to be asked questions, set challenges, or made to keep up. Presence is the
product; engagement is the risk.

### 1.22 The nearest thing to us that already exists

Every other source in this document is a companion built by a studio into its
own game. Inigo is the exception and the closest analogue we have: a fan-made,
fully-voiced follower added by mod to somebody else's long-session RPG, kept on
by players across hundreds of hours, and uninstallable in one click. It has
4.5M+ downloads and is well enough regarded that Bethesda's own director has
praised it [43][44].

What it does, in the terms this document has been using:

- **Scale.** 7,000+ lines, one writer and one voice actor, built over more than
  a decade [43][44]. We have 1,470.
- **Contextual triggers.** Comments on locations, quests, other followers and
  specific NPCs; expresses unease in particular ruin types; reacts to crimes
  the player commits and to NPCs they help [44]. This is the reactivity of §1.4
  applied to an existing world the mod did not author — the same position we are
  in.
- **A hidden friendship stat** fed by time together, quests completed and
  dialogue chosen. Topics unlock as it rises, and the personal questline is
  gated on friendship rather than on story progress, unfolding across 20–30
  hours [44].
- **Idle behaviour that points at the player.** Rather than standing silent he
  initiates conversation on the road — asking about *your* motivations, offering
  observations, telling stories from his own past [44].
- **Behaviour when left behind.** Sent home, he uses the space: sits in chairs,
  works at crafting stations, comments on the decor [44].
- **Repetition strategy: volume.** The write-up is explicit that no mechanism
  prevents repeats; the approach is simply to have enough unique content [44].

**(inference)** Three things to take from this. First, a scale check — the
most-loved thing in our category is roughly five times our size, which suggests
our 1,470 lines is early rather than complete. Second, a caution about our own
cleverness: Inigo achieves what it achieves with *no* director, *no* shuffle
bag and *no* one-time machinery. Volume substitutes for all of it. Our machinery
is a way of getting more out of less content, which is the right trade for us,
but it is not what the benchmark did. Third, and most usefully: the two Inigo
behaviours we most obviously lack are idle conversation *about the player* and
doing something with the space when told to stay.

### 1.23 Testing whether a companion is annoying

The methodology literature is unglamorous and consistent. Qualitative signal
comes from **direct observation, think-aloud protocols and structured
interviews**, which is what explains the *why* behind behaviour; quantitative
signal comes from instrumenting the thing and looking at what actually happened
[45][46]. Recommended practice is to run both and to test with more than one
kind of player [45][46].

**(inference)** We have done neither. Every judgement in three rounds of
building — is it too chatty, does it repeat, does that joke land — has rested on
the user's in-game impressions and on `talkrate.py`, which is a *model* of what
the rules could do rather than a record of what they did. The plugin holds one
`lastSpokenText` and some `log.debug` calls that are off by default, so an hour
of play leaves no reviewable trace at all.

The cheap fix is instrumentation rather than methodology: a rolling session
transcript — timestamp, rule id, line — written to a file and readable after the
fact. That converts "did it feel repetitive" into a question with an answer, and
it is the only way to check whether the pacing work in Tier 1 actually did
anything.

### 1.24 Keeping one voice across a large corpus

Standard practice for a dialogue corpus is a **living style guide** that defines
the narrative voice and is updated as the project changes, plus distinguishing
characters by vocabulary, syntax and punctuation rather than by content alone,
and **reading lines aloud** to check they sound like speech [47].

**(inference)** We have 1,470 lines written across many sessions with the voice
carried entirely in working memory. That is precisely the condition in which a
corpus drifts, and it has already happened once: the 82-line batch written two
rounds ago had zero contractions against 30% in the rest of the file, and only
turned up because it was measured. A short written voice document — the follower
is dry, British, understated, contracts everything, never explains a joke, never
uses two sentences where one will do — would have caught it before it shipped,
and gives any future contributor something to write against.

---

## Part 2 — Context: what makes our case unusual

- **No story to hang significance on.** Per §1.11 a companion needs high
  significance to story or gameplay. We have neither by default — the follower
  is cosmetic and cannot affect OSRS gameplay. **(inference)** Our substitute
  must be *significance to the player's own history*: it is the thing that was
  there, and the only entity that remembers.
- **Enormous session lengths and repeat exposure**, far past where Stardew's
  villagers collapse.
- **Much of that time is low-attention.** OSRS is heavily AFK/grind; the
  follower often speaks to someone who is not looking.
- **The player did not choose a character.** They dressed one. Personality has
  to arrive without a script.
- **Native OSRS pets are pure prestige** — rare drops, aesthetic only, no
  personality [17]. We are offering something the game deliberately does not.
- **We cannot be a burden in the mechanical sense** — the follower cannot block,
  aggro, or die. §1.1's burden failure mode is largely closed to us, which means
  our whole risk budget sits on the *noise* side. **(inference)**

---

## Part 3 — Audit of the current follower

### Already aligned

| Finding | What we have |
|---|---|
| Reactivity (§1.4) | 368 context-gated rules; tallies, records, incidents, place memory, first-meeting date |
| Contextual response, the Oblivion failure (§1.3) | Talk-to varies by state; a question replaces the everyday tree |
| Self-motivation / own agenda (§1.7) | Errands, wants, souvenirs, bets, games — all follower-initiated |
| Care loop reciprocity (§1.8) | Mood, wants kept/missed, advice heeded/ignored |
| Not a burden (§1.1) | Stands clear of fights, teleports when stranded, notices being underfoot |
| Proximity (§1.1) | Follows closely by default |
| Silence as a tool (§1.10) | Mood-scaled speech gap, 3s floor, hush |
| Player-initiated conversation (§1.10) | Right-click Talk-to; menu answers |
| Generated not authored (§1.13) | Counts, records, dates, places, nicknames |
| Idle library (§1.12) | Fidgets, rest, wander, errands |
| Affordance (§1.12) | Right-click emotes, Stay/Send, Talk-to |

This is a strong position. The gaps are narrower than the list below suggests.

### Gaps and risks

**G1 — Variant counts far below practice. (§1.2)** Median 3 per speaking rule;
9 rules have 1; **99 have exactly 2**. Norm is 8–20+ for anything heard often.

**G2 — The picker turns 2-variant rules into perfect alternation. (§1.2)**
`SpeechRule.pickPhrase()` re-rolls until the index differs from the last. With
two variants that is a guaranteed A-B-A-B cycle — *more* predictable than plain
random. A defect, not a tuning question.

**G3 — Repeat-avoidance is per-rule, never global. (§1.2)** No corpus-level
"exhaust before repeating", which is Valve's *default* behaviour [25].

**G4 — No one-time content. (§1.2, §1.4)** No line is ever retired or reserved
for a first occasion. Valve ships this as `norepeat` [25]; Hades treats it as
the highest-value content per word [10].

**G5 — No pacing director. (§1.5)** Cooldowns are per-rule and wall-clock. There
is no intensity model and, critically, **no enforced relax period** after a
burst. Nothing prevents three different rules firing in nine seconds.

**G6 — We speak on timers, not at boundaries. (§1.6)** `idle` is our main
ambient gate, but idle ≠ between tasks; a player at a furnace is both idle and
mid-task. We have the boundary events (combat end, level up, region change,
inventory full) and do not use them as *permission to speak generally*.

**G7 — Unaudited callout risk. (§1.4)** `something-big-nearby` is exactly the
Ellie bad-callout pattern: if the player looks and sees nothing, the follower
reads as stupid. Same risk for any line asserting something the player will
check.

**G8 — Urgency changes priority but not register. (§1.6)** We shout in
`critical-hp`, which is right, but urgency is not systematically reflected in
line length or phrasing elsewhere.

**G9 — The follower never disagrees or is wrong on purpose. (§1.9)** Bets are
the only place it can be wrong, and being wrong costs it nothing.

**G10 — The relationship is nearly one-directional. (§1.8, §1.12)** The player
cannot do anything *for* the follower except take it somewhere.

**G11 — Neglect is tracked; attention is barely rewarded. (§1.8)** The design
leans on the punishment side — the named resentment failure mode [9].

**G12 — Talking is gated on time, never on attention. (§1.6)** We detect the
unattended case and only use it for a joke.

**G13 — No sense of the follower changing over time. (§1.3)** Its voice at hour
500 is its voice at hour 1.

**G14 — Everything is said at one volume. (§1.4)** We have priority for conflict
resolution but no notion of a line being a *big moment*.

**G15 — No world-interaction ("explore") behaviour. (§1.12)** Errands are
scripted and self-contained; the follower never reacts to a specific thing in
the scene. Naughty Dog got outsized value here for a day's work [19].

**G16 — Display duration is fixed while line length is not. (§1.14)**
`speechDurationMs` defaults to 4000ms for every line. At the comfortable 17 CPS
that affords 68 characters; **95 of our 1470 lines are longer than that**, and
11 exceed even the 20 CPS ceiling. The worst is 103 characters and needs ~6.1
seconds. Median is 48 characters, so the fix is narrow — but the lines that
overflow are disproportionately the *good* ones (area flavour, boss advice),
which are exactly the lines worth reading.

**G17 — No player-facing chattiness control. (§1.14, and finding 18)**
Config exposes `globalCooldownMs` as a raw millisecond box labelled "Minimum gap
(ms)". Forspoken's praised version is four named levels [33]. Group toggles let
players silence *categories*, which is a blunter instrument than a rate.

**G18 — The memory has no surface and no eraser. (§1.16)** Everything is in an
opaque config blob. A player cannot see what the follower knows about them or
clear it, which is the named mitigation for the invasiveness risk [30].

**G19 — Jokes and observations go through identical machinery. (§1.15)** Same
cooldown model, same variant treatment, same repeat-avoidance. A joke needs to
be rarer and retired sooner than an observation; nothing in the rule format can
express the difference.

**G20 — The first impression is a guidebook, not a character. (§1.18)** Measured
across the corpus by marking every condition that needs history the player does
not have on day one:

| | day-one lines | locked behind history |
|---|---|---|
| About the world (gear / area / boss / quest) | 900 | 22 |
| About us (idle / memory / reactions / …) | 195 | 149 |

**Day one is 82% world-facts and 18% personality**, and 43% of the personality
content is gated. 55 speaking rules cannot fire at all until history exists. The
new player walking through Varrock meets an encyclopaedia that follows them —
good lines, but the wrong ones for deciding to keep the plugin.

**G21 — Nothing invites the player to make it theirs. (§1.19)** The follower
ships named "Follower". Outfits, colours and profiles all exist and are good,
but they sit in a config section and a side panel, and nothing in the first
session points at them. The Talk-to script even has the follower say "You gave
me one" about its name — a line that is false for any player who never opened
the settings.

**G22 — Earned taste fully overrides rolled taste. (§1.20)** Once a region's
score passes ±40 the roll no longer matters, so the follower's opinions converge
on the player's own history. The adaptation research says keep a core immune to
experience [41].

**G23 — Several systems ask things of a player who came to be calm. (§1.21)**
Questions, challenges, wants and advice all solicit a response. Each is
individually well-gated, but nothing knows that the player is mid-grind and has
chosen a low-demand state; `idle` reads a furnace session as a good moment to
propose a game.

**G24 — Nothing records what the follower actually said. (§1.23)** One
`lastSpokenText`, and `log.debug` calls that are off by default. Three rounds of
tuning decisions have been made without a transcript, and the Tier 1 pacing work
has no way to be verified after the fact.

**G25 — No style guide for 1,470 lines. (§1.24)** The voice exists only in
working memory, which has already produced one measurable drift (the
zero-contraction batch). Nothing a future contributor could write against.

**G26 — Told to stay, the follower just stands there. (§1.22)** Inigo's
left-at-home behaviour — using the space, sitting, commenting — is one of its
most-praised touches. Our Stay mode is a null state, and it is the situation in
which the follower is most visible and least busy.

**G27 — Idle chatter is about the follower, never about the player. (§1.22)**
Inigo asks about *your* motivations. Our idle lines are observations, complaints
and jokes from the follower's side; nothing turns the attention around and asks
the player something with no mechanical purpose.

---

## Part 4 — Recommendations, in priority order

### Tier 0 — Straight defects, cheap to fix

**R0a. Scale display duration to line length.** `max(1500ms, characters / 17 *
1000)`, capped at some sensible ceiling, instead of a flat 4000ms [27][28].
Fixes G16 for every line at once and costs a few lines of code.

**R0b. Lint line length.** With duration scaled, over-long lines stop being
unreadable and start being *slow* — still worth a threshold. Add a test in the
style of the existing glyph check: flag anything that would need more than ~5
seconds at 17 CPS, so a new line cannot quietly become a wall.

**R0c. Give chattiness a named setting.** Four levels — Quiet / Occasional /
Normal / Chatty — mapping onto the director's base gap, replacing the raw
milliseconds box [33] (G17).

**R0d. Ask for a name in the first session.** One prompt, skippable, the first
time the follower is spawned. It costs nothing, it converts a stranger called
"Follower" into something the player made a decision about, and delayed
customisation is a named churn cause [40] (G21). Point at the outfit panel in
the same breath.

### Tier 0b — The first twenty minutes

**R0e. Front-load personality, not facts.** The cheapest fix for G20 is not new
content but *ordering*: hold back some of the encyclopaedic gear/area/quest
rules early (a `daysKnown`-style gate in reverse), and let the small stock of
idle, reaction and errand lines carry the opening instead. A follower that says
less but sounds like someone beats one that recites Reldo's job.

**R0f. Write a short arrival arc.** A handful of one-time lines for the first
spawn, the first hour, and the first return the next day — the moments where the
follower has nothing else to say because it has no history. This is the highest
value use of the one-time mechanism in R9, and it directly targets the window
that decides whether the plugin survives [37][38].

**R0g. Make one early moment characterful on purpose.** An early win is standard
onboarding advice [37][38] and we have no equivalent. Something small in the
first session that could only be this follower — a souvenir picked up, an
opinion offered, a question asked — scheduled rather than left to chance gates
that may not fire for hours.

### Tier 1 — Pacing and repetition machinery

**R1. Build a speech director.** Port the Director model [18]: a running
"chatter intensity" that rises each time the follower speaks (weighted by the
line's importance), decays over time, and — crucially — **enforces a relax
period of 30–45 seconds after a peak** during which only essential lines pass.
This addresses G5 and is the highest-leverage change available, because contrast
is what makes the good lines land.

**R2. Replace `pickPhrase` with a shuffle bag.** Draw without replacement,
reshuffle when empty, never let the last of one bag be the first of the next.
Removes the A-B-A-B tell on 99 rules. This is Valve's default behaviour, not an
enhancement [25].

**R3. Add a corpus-level recently-said window.** Suppress any line said in the
last N lines or M minutes across all rules, so the follower cannot ping-pong
between two nearby rules either [10][25].

**R4. Raise variant counts only where it matters — and only for observations.**
Use the existing `talkrate.py` model to rank rules by expected firings/hour and
bring the top ~30 to 8+ variants [3]. Leave the once-a-year rules at 2.

Round 3 qualifies this: the trade is different for jokes. Randomising a quip
buys repetition-tolerance by giving up the on-point reactivity that made it
funny [29]. So split the treatment — **observations get many variants and an
even shuffle; jokes get few variants, tighter conditions, longer cooldowns, and
early retirement** (G19). A `kind: joke` marker on the rule is enough for the
director and the retirement pass to tell them apart.

**R5. Compose the highest-frequency lines from parts.** Opener / body / tag
slots; twelve fragments can outrun forty sentences [15]. Heed the sensitivity
warning: one weak fragment then shows up everywhere [15].

### Tier 2 — Speak at the right moment about the right thing

**R6. Prefer boundaries to timers.** Add a `boundary` condition true for a few
ticks after something *ends* — combat, a level, an arrival, a bank trip — and
move ambient chatter onto it [20][21]. Keeps the follower out of the middle of
tasks (G6).

**R7. Audit every observational line for verifiability.** Any line asserting
something the player will look for must be right, or not said. Tighten
`something-big-nearby` to a confirmed, currently-visible NPC. A bad callout is
worse than silence [19] (G7).

**R8. Let urgency change register, not just priority.** Urgent lines short and
clipped, non-urgent longer and more relaxed [21] (G8).

**R9. Introduce one-time lines** (`once: true`, persisted). Reserve for firsts:
first boss kill, first death in a region, the hundredth of anything. Valve's
`norepeat` [25]; the highest value per word written [10] (G4).

**R10. Add tiers: evergreen / conditional / occasion.** Distinct from priority.
An occasion line takes the floor, holds still, and is the only thing said for a
while [10] (G14).

**R11. Increase specificity of existing lines.** Prefer *how* over *that* —
Hypnos comments on how you died [10]. We have cause, place, count and time of
day, and mostly say the generic thing.

### Tier 3 — Make the relationship two-way

**R12. Give the player something to do *for* the follower** beyond taking it
somewhere: answer its mood, accept or decline a challenge, give it something
[16] (G10).

**R13. Rebalance the ledgers toward reward.** Positive side louder than the
negative, and always a route back — "suffers but recovers" [9]. Audit that no
state leaves the follower permanently sour (G11).

**R14. Let it be wrong, and let it disagree.** Opinions it defends, predictions
that cost it something, occasional refusal when in a low mood [12][5] (G9).

### Tier 4 — Long-horizon health

**R15. Scale speech to attention.** Use `unattended` as a global damper on the
director, not just a joke trigger [13] (G12).

**R16. Let the voice change with time known.** We store the first meeting: more
formal early, more familiar later [13]. Cheap as a `daysKnown` gate on alternate
phrasings (G13).

**R17. Retire content the player has heard to death.** Per-line say counts;
past a threshold, deprioritise in favour of anything unheard [4] (G3, G1).

**R18. Context-swap whole bark sets** by region, activity, or era of the
relationship, so the pool itself evolves [3].

**R19. Add an "explore" behaviour.** Let the follower notice and walk to a
specific thing in the scene — a fire, a chest, an altar, a body of water — and
react to *that thing*. Naughty Dog's version took about a day and drew
disproportionate praise [19] (G15). This is also our only realistic route to
BioWare's "companion with its own life": we have no cast for it to relate to, so
it has to relate to the world [36] (§1.17).

### Tier 4b — Stay on the safe side of knowing too much

**R20. Give the memory a surface and an eraser.** A panel showing what the
follower currently knows — tallies, records, the incident, places, the date it
met you — plus a button to clear it. This is the named mitigation for the
invasiveness risk [30], it makes three rounds of hidden machinery legible, and
it doubles as a debugging tool (G18).

**R21. Keep memory claims singular and warm.** **(inference)** The risk is
aggregate, not per-fact: one recalled detail reads as attentive, three stacked
in a line read as a dossier. Add a writing rule — one remembered fact per line —
and prefer the warm framing of a number over the number itself.

**R22. Keep the quirks.** The uncanny research argues *for* deliberate minor
flaws rather than smoothing them out [31], and anthropomorphism is a dial to
calibrate rather than maximise [32]. Concretely: resist the urge to make the
follower more articulate, more accurate, or more consistently pleasant than it
currently is. Its wrongness is a feature.

**R23. Cap how far earned taste can move.** Keep a small core of rolled
preferences that experience can never override — a place it likes for no reason
it will give. Over-adaptation measurably reduces perceived authenticity and
trust [41]; the follower should have opinions the player cannot argue it out of
(G22). Cheap: exempt one or two rolled regions from the earned-score override.

**R24. Let the player's activity gate the demanding systems.** Questions,
challenges and wants should not open while the player is mid-task in a
repetitive activity — the state they deliberately entered for calm [42]. The
`repeating` condition already detects exactly this and is currently used only to
make the follower join in; negated, it is the guard these systems need (G23).

### Tier 4c — Borrowed from the nearest analogue

**R25. Give Stay mode something to do.** The follower left behind should use the
spot — sit, look around, poke at something nearby, remark on the place when you
return. Inigo's version of this is among its most-praised details [44], and Stay
is exactly when the follower is most watched and least occupied (G26).

**R26. Let it ask about the player.** Idle lines that turn the attention around
— why we came here, what we're saving for, whether we're staying long — with no
mechanical purpose and no answer required [44] (G27). The `asks` machinery
already supports a real answer where one is wanted; most of these should not
want one.

### Tier 5 — How we should work

**R27. Instrument the thing before tuning it.** A rolling session transcript —
timestamp, rule id, line — to a file, readable afterwards. Cheap, and it is the
precondition for every other recommendation in Tiers 0–2 being verifiable rather
than believed [45][46] (G24). Do this *first*: the pacing work in R1 is
unmeasurable without it.

**R28. Write the voice document.** Half a page: dry, British, understated,
contracts everything, one thought per line, never explains a joke, never uses
two sentences where one will do — plus the ASCII/glyph rule and the
one-remembered-fact rule from R21. It would have caught the zero-contraction
batch before it shipped [47] (G25).

**R29. Take the scale check seriously.** At 1,470 lines against the benchmark's
7,000 [43][44], the honest reading is that we are early. R4's targeted variant
work is the efficient version of closing that, but the gap is real and no amount
of machinery fully substitutes for content.

**R30. Spend the next round tuning, not adding.** Naughty Dog spent the majority
of a from-scratch rebuild on iteration rather than new systems, with an "almost
manic obsession" over follow positioning and ambient dialogue triggers, and
concluded the key is performance nuance rather than system complexity [19]. We
have shipped eleven systems in three rounds and tuned almost nothing. Tiers 1–2
are almost entirely tuning.

### Explicitly not recommended

- **More rules for their own sake.** At 368 the constraint is variants per rule,
  pacing, and specificity — not rule count (G1, G3, G5).
- **Making the follower mechanically useful.** A valid route to significance [1]
  but closed to us: a client-side plugin must not affect play.
- **Louder or more frequent speech.** Every source points the other way.
- **Chasing novelty with volume alone.** Habituation is automatic [22]; timing
  variability and personal relevance do more per unit of effort than word count
  **(inference)**.
- **Randomising the jokes to make them last.** It works, and it costs the
  reactivity that made them funny [29]. Rarity is the better lever.
- **Making the follower know more.** Three rounds of memory is enough to work
  with. The next marginal fact is more likely to tip toward invasive than to add
  charm [30][32] **(inference)**.

---

## Sources

1. Bouquet, Mäkelä & Schmidt — *Exploring the Design of Companions in Video
   Games*, Mindtrek '21. [PDF](https://www.medien.ifi.lmu.de/pubdb/publications/pub/bouquet2018mindtrek/bouquet2018mindtrek.pdf)
2. *Endure and Survive: the AI of The Last of Us* — Game Developer. [link](https://www.gamedeveloper.com/design/endure-and-survive-the-ai-of-the-last-of-us)
3. *Voice Direction in Games: Why Combat Barks Are Harder Than Cutscenes*. [link](https://www.toosixmg.com/post/voice-direction-in-games-why-combat-barks-are-harder-than-cutscenes)
4. Stardew Valley NPC dialogue discussion. [link](https://steamcommunity.com/app/413150/discussions/0/1728701877515466435/) ; Fallout 4 companion repetition. [link](https://steamcommunity.com/app/377160/discussions/0/492378265887509276/)
5. Fumito Ueda on Trico. [Wikipedia](https://en.wikipedia.org/wiki/The_Last_Guardian) ; [genDESIGN symposium](https://www.gendesign.co.jp/qa_01/interview04en.html)
6. Navi and the funneling companion. [Wikipedia](https://en.wikipedia.org/wiki/Navi_(The_Legend_of_Zelda))
7. *Ellie's buddy AI in The Last of Us explained at GDC 2014*. [link](https://www.gamedeveloper.com/design/ellie-s-buddy-ai-in-i-the-last-of-us-i-explained-at-gdc-2014)
8. *Can we Fix Escort Mission Game Design?* [link](https://www.gamedeveloper.com/design/can-we-fix-escort-mission-game-design-)
9. Yu-kai Chou — *Pet Companion Design: Why Virtual Pets Win Retention*. [link](https://yukaichou.com/advanced-gamification/the-pet-companion-design-in-gamification/)
10. Christi Kerr — *How the Dialogue System in Hades Rewards Failure*. [link](https://www.christi-kerr.com/post/how-the-dialogue-system-in-hades-rewards-failure) ; [Kasavin interview](https://www.gameshub.com/news/features/hades-greg-kasavin-breaks-down-supergiants-unique-approach-to-narrative-262459-2193/)
11. *Talking Firewatch: Playing Through Dialogue*. [link](https://frostilyte.ca/2021/04/14/talking-firewatch-playing-through-dialogue/)
12. *Why Your AI Companion Feels Flat Over Time*. [link](https://www.roborhythms.com/why-ai-companion-feels-flat/)
13. Loyall & Bates — *Believable Agents: Building Interactive Personalities*,
    CMU-CS-97-123. [PDF](https://www.cs.cmu.edu/Groups/oz/papers/CMU-CS-97-123.pdf)
14. Emmerich, Ring & Masuch — *I'm Glad You Are on My Side*, CHI PLAY 2018
    (N=237). [link](https://dblp.org/rec/conf/chiplay/EmmerichRM18.html)
15. *How to maintain immersion (+ reduce repetition & listening fatigue) in game
    audio* — A Sound Effect. [link](https://www.asoundeffect.com/game-audio-immersion/)
16. *Can You Pet the Dog?* — Washington Post. [link](https://www.washingtonpost.com/video-games/interactive/2021/can-you-pet-the-dog/)
17. Pet — OSRS Wiki. [link](https://oldschool.runescape.wiki/w/Pet)
18. Michael Booth — *The AI Systems of Left 4 Dead*, Valve/GDC 2009. [PDF](https://steamcdn-a.akamaihd.net/apps/valve/2009/ai_systems_of_l4d_mike_booth.pdf)
19. Max Dyckhoff — *Ellie: Buddy AI in The Last of Us*, Game AI Pro 2 ch. 35. [PDF](http://www.gameaipro.com/GameAIPro2/GameAIPro2_Chapter35_Ellie_Buddy_AI_in_The_Last_of_Us.pdf)
20. *Cognitive Flow: The Psychology of Great Game Design* — Game Developer. [link](https://www.gamedeveloper.com/design/cognitive-flow-the-psychology-of-great-game-design) ; and player-psychology UX summary. [link](https://nastyrodent.com/player-psychology-in-game-ux/)
21. Edwards, Janssen, Gould & Cowan — *Eliciting Spoken Interruptions to Inform
    Proactive Speech Agent Design*, CUI 2021. [PDF](https://arxiv.org/pdf/2106.02077)
22. *Habituation of reinforcer effectiveness* — Frontiers in Integrative
    Neuroscience. [link](https://www.frontiersin.org/journals/integrative-neuroscience/articles/10.3389/fnint.2013.00107/full) ; *Novelty is not surprise*. [link](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC8205159/)
23. *Variable Rewards and the Psychology of Sustained Engagement*. [link](https://gauravtiwari.org/variable-rewards-psychology-sustained-engagement/)
24. Valve Developer Community — *Response System*. [link](https://developer.valvesoftware.com/wiki/Response_System)
25. Valve Developer Community — *Response* (response group options:
    `permitrepeats`, `sequential`, `norepeat`). [link](https://developer.valvesoftware.com/wiki/Response)
26. *Fading Novelty*. [link](https://www.lesswrong.com/posts/qPKbLpSRRw89zdkJn/fading-novelty)
27. *Optimizing Game Subtitles for Readability and Timing*. [link](https://salivity.github.io/game-development/article/optimizing-game-subtitles-for-readability-and-timing)
28. *The Science of Readability: Optimal Subtitle Font Size & Speed*. [link](https://vsubtitle.com/subtitle-font-size-and-reading-speed-2026/) ; Subtitling.net reading-speed standards. [link](https://subtitling.net/standards/subtitle-reading-speed)
29. *Comedy in videogames* — Cane and Rinse. [link](https://caneandrinse.com/comedy-videogames/)
30. *How Reliable Is Long-Term AI Companion Memory?* [link](https://geteuvola.com/blog/how-reliable-is-ai-companion-memory)
31. *The Uncanny Valley of AI Companions: Why Hyper-Realism Backfires* — Wayline. [link](https://www.wayline.io/blog/uncanny-valley-ai-companions)
32. *Anthropomorphic response: understanding interactions between humans and AI
    agents* — Computers in Human Behavior. [link](https://www.sciencedirect.com/science/article/abs/pii/S0747563222003326) ; *Talking body* — Frontiers in Robotics and AI. [link](https://www.frontiersin.org/journals/robotics-and-ai/articles/10.3389/frobt.2024.1456613/full)
33. *Forspoken has one incredible feature every AAA game should steal* (Cuff
    Chat Frequency). [link](https://www.inverse.com/gaming/forspoken-cuff-chat-frequency-setting-banter-slider)
34. *How to Think Like a Comedy Writer* — MasterClass. [link](https://www.masterclass.com/articles/how-to-think-like-a-comedy-writer)
35. *Comedic Timing* — Screenwriters Gym. [link](https://screenwritersgym.substack.com/p/comedic-timing)
36. *A Deep Dive Into BioWare's Companion Design Philosophy in Dragon Age: The
    Veilguard* — Game Informer. [link](https://gameinformer.com/exclusive/2024/07/15/a-deep-dive-into-biowares-companion-design-philosophy-in-dragon-age-the)
37. *First-Time User Experience (FTUE) in Mobile Games* — Udonis. [link](https://www.blog.udonis.co/mobile-marketing/mobile-games/first-time-user-experience)
38. *Day 1 to Day 7 Retention: How To Make Players Stay*. [link](https://maf.ad/en/blog/game-retention/) ; *Game retention: 12 strategies*. [link](https://featureupvote.com/blog/game-retention/)
39. *The Effects of Avatar-Based Customization on Player Identification*. [link](https://www.academia.edu/99092558/The_Effects_of_Avatar_Based_Customization_on_Player_Identification) ; *What factors affect psychological ownership when creating an avatar?* [link](https://www.sciencedirect.com/science/article/abs/pii/S0736585324000029)
40. Yu-kai Chou — *Avatar Design: The Psychology of Digital Identity*. [link](https://yukaichou.com/advanced-gamification/the-avatar-gamification-design-technique/)
41. *The Adaptation Paradox: Agency vs. Mimicry in Companion Chatbots*, arXiv
    2509.12525. [PDF](https://arxiv.org/pdf/2509.12525)
42. *Why Grind? The psychology and culture of repetitive gameplay* — MMORPG.com. [link](https://www.mmorpg.com/features/why-grind-the-psychology-and-culture-of-repetitive-and-uninteresting-gameplay-2000131403) ; *Stop MMO burnout and enjoy grinding*. [link](https://www.rpgstash.com/blog/stop-mmo-burnout-and-enjoy-grinding)
43. INIGO — Skyrim Special Edition Nexus mod page. [link](https://www.nexusmods.com/skyrimspecialedition/mods/1461) ; *Why Todd Howard Loves This Follower Mod* — CBR. [link](https://www.cbr.com/skyrim-inigo-follower-mod-todd-howard-bethesda/)
44. *Inigo Skyrim: The Complete Guide to the Most Beloved Follower Mod*. [link](https://d3timer.com/inigo-skyrim-the-complete-guide-to-the-most-beloved-follower-mod-in-2026/)
45. *Playtesting 105: How to Measure Qualitatively* — Game Developer. [link](https://www.gamedeveloper.com/design/playtesting-105-how-to-measure-qualitatively)
46. *Research goals and methods for playtesting* — PlaytestCloud. [link](https://start.playtestcloud.com/blog/methods-for-playtesting) ; *Playtesting: Measuring Player Experience*. [link](https://getcreativetoday.com/playtesting-measuring-player-experience/)
47. *What's the best way to create and keep a style guide for your game?* [link](https://www.linkedin.com/advice/3/whats-best-way-create-keep-style-guide-your-game-skills-game-design) ; *Dialogue Writing* — Meegle. [link](https://www.meegle.com/en_us/topics/game-design/dialogue-writing)

### Still not retrieved

- Elan Ruskin, *AI-driven Dynamic Dialog through Fuzzy Pattern Matching*
  (GDC 2012) — still behind GDC Vault. Round 2 substantially closed this gap
  from the other end: the Valve Developer wiki documents the shipped Response
  System [24][25], which is the same lineage. The talk would still be worth
  watching. [video](https://www.youtube.com/watch?v=tAbBID3N64A)
- Emmerich et al. full text and the 2024 *Companion Design Scale* — abstracts
  only; the factor structure would sharpen Part 3.

---

## Changelog

**Round 2 (2026-08-10)** — added sources 18–26 and rewrote around them:

- **Recovered the Ellie chapter in full** [19] by writing a ToUnicode-CMap-aware
  PDF extractor; round 1 only had second-hand coverage. It supplied the
  proximity/memorability point, the attribution principle, the bad-callout
  finding, the explore system, the anti-cheating stance, and the
  iteration-over-systems conclusion.
- **Added the AI Director pacing model with real numbers** [18], which became
  the new top recommendation (R1) — round 1 had no pacing model at all.
- **Found the shipped Valve Response System docs** [24][25], which turn round
  1's R1/R5 from proposals into "match what Valve ships by default".
- **Added the interruption and cognitive-load literature** [20][21], producing
  the boundary-versus-timer recommendation (R6) and the urgency-register one
  (R8).
- **Added the habituation literature** [22][23][26], which qualifies the
  "write more variants" advice: timing variability and personal relevance beat
  raw volume.
- Findings grew 10 → 14, gaps 10 → 15, recommendations 14 → 20.

**Round 3 (2026-08-10)** — added sources 27–36. This round went looking for
things the first two had assumed rather than checked:

- **Found a live defect.** Every bark source in rounds 1–2 assumed *recorded
  voice*; we ship *text*. The subtitling standards [27][28] give a hard budget —
  17 characters per second, 1.5s floor — and measuring our corpus against our
  fixed 4000ms display found **95 lines that cannot be read in the time they are
  shown**. New Tier 0 (R0a–R0c), and the cheapest real improvement in the whole
  document.
- **Qualified the headline recommendation.** Round 1's finding 1 was "write more
  variants". The comedy research [29] shows randomising a *joke* buys
  repetition-tolerance at the cost of the reactivity that made it land. Split
  the advice: many variants for observations, rarity and specificity for jokes
  (R4, G19).
- **Added the countervailing evidence on memory** [30][31][32]. Three rounds
  have been spent adding things the follower knows about the player; this is the
  first source set saying where that stops helping. New Tier 4b (R20–R22) and a
  new "not recommended": stop adding facts.
- **Added a shipped, praised precedent for a chattiness setting** [33] — four
  named levels rather than our raw milliseconds box.
- Added BioWare's own-life philosophy [36], which mostly reinforces the existing
  explore recommendation rather than adding a new one.
- Findings 14 → 18, gaps 15 → 19, recommendations 20 → 26.

**Round 4 (2026-08-10)** — added sources 37–42. Rounds 1–3 all asked "is the
follower good once you know it"; this round asked the questions that come
before and around that.

- **Audited the first session and found the biggest structural gap yet.** By
  marking every condition that needs history a new player does not have,
  **day one measures 82% world-facts to 18% personality**, with 43% of the
  personality content gated. The follower's first impression is an encyclopaedia
  that walks. Given that confusion kills early retention and uninstalling a
  plugin is one click [37][38], this now sits in a new Tier 0b (R0e–R0g) as the
  most valuable *unbuilt* work in the document.
- **Found the cheapest attachment win we have been ignoring.** Psychological
  ownership follows invested effort, cosmetic customisation beats functional for
  identification, and delaying customisation is a named churn cause [39][40].
  Our customisation is deep and entirely passive — the follower ships called
  "Follower", and the Talk-to script's "you gave me one" is false for anyone who
  never opened settings. R0d: ask for a name on first spawn.
- **Upgraded round 2's friction finding from blog to experiment** [41], and it
  now argues against something we shipped: earned taste currently overrides
  rolled taste completely, so the follower's opinions converge on the player's
  history. Over-adaptation measurably reduces authenticity and trust. R23 caps
  it (G22).
- **Reframed the brief.** Grinding is often chosen *because* it is repetitive —
  a calm, low-decision, mindful state — while also being social [42]. That
  justifies the plugin and constrains it: presence is the product, engagement is
  the risk. R24 gates the demanding systems behind `repeating` (G23).
- Findings 18 → 22, gaps 19 → 23, recommendations 26 → 32.

**Round 5 (2026-08-10)** — added sources 43–47. **This round returned less new
external ground than any before it**, and that is itself a result: the design
literature on companions is close to exhausted for our purposes. What it did
produce came from looking sideways rather than deeper.

- **Found the nearest real analogue and it is a mod, not a game.** Inigo — a
  fan-made Skyrim follower kept on across hundreds of hours and uninstallable in
  one click — is the only source in this document facing our actual constraints
  [43][44]. Two uncomfortable readings: it has **7,000+ lines to our 1,470**, and
  it achieves what it achieves with no director, no shuffle bag and no one-time
  machinery. Volume substitutes for all of our cleverness. Our machinery is the
  right trade for a smaller corpus, but the scale gap is real (R29).
- **Two Inigo behaviours we plainly lack**: doing something with the space when
  told to stay (G26, R25), and idle lines that ask about *the player* rather
  than reporting on the follower (G27, R26).
- **Named the methodological hole.** Playtesting is observation plus
  instrumentation [45][46]; we have done neither, and the plugin leaves no
  reviewable trace of a session. Every tuning judgement across three rounds has
  rested on impressions and a model. R27 — a session transcript — is now the
  *first* thing to build, because the Tier 1 pacing work cannot otherwise be
  verified.
- **Named the writing hole.** 1,470 lines, no style guide [47]. The drift this
  predicts has already happened once and was only caught by measurement (R28).
- Findings 22 → 26, gaps 23 → 27, recommendations 32 → 37.

**On saturation.** Rounds 1–3 each overturned something. Round 4 found one large
structural gap. Round 5 found a benchmark and two process gaps. A sixth round of
the same kind is unlikely to pay; the open questions now are empirical — what
does an hour of this actually sound like — and R27 is the thing that answers
them.

---

## What has been built

**Tier 0 — shipped 2026-08-10.** R0a (reading time), R0b (length lint), R0c
(named chattiness). Every line now holds the floor for as long as it takes to
read at a configurable 17 characters a second, and the lint that came with it
caught three lines nobody could have finished.

**Tier 0b and the director — shipped 2026-08-10.**

R1 landed as `SpeechDirector`: intensity rises one unit per spoken line, decays
one unit per five speech gaps, and crossing 2.5 buys 30–45 seconds during which
only an occasion gets through. Everything is expressed in multiples of the base
gap, so the chattiness setting moves the whole model rather than fighting it —
at Quiet, three lines are already far enough apart that they never peak, which
is correct, because that player has been given their contrast already.

R10 landed narrower than written. The three-tier scheme (evergreen /
conditional / occasion) collapsed to the one distinction that has behaviour:
`occasion: true`, meaning *this gets through a relax period and spends no
intensity of its own*. Sixty-eight rules carry it, and they divide into two
kinds that the recommendation did not separate — the moment worth marking (the
pet, the anniversary, the place it asked to be taken) and **the warning that has
to land**. If the player is about to die and the director has just told the
follower to be quiet, silence is a worse failure than repetition could ever be.
All eight health warnings and all forty-seven boss identifications are
occasions for that second reason, not the first.

R0e was implemented as policy rather than as 219 rule edits: while
`sessionCount <= 2`, area and gear lines must clear four gaps between them. This
is the settling-in damper, and it decays on its own without anything needing to
remember to remove it.

R9 (one-time lines) came forward from Tier 2 because R0f depends on it. Spent
ids live with the tallies rather than on the rule, which is what makes them
survive the rule reload that fires whenever `phrases.json` is edited.

R0f and R0g are four one-time lines — `first-meeting`, `first-page`,
`first-hour`, `first-return` — covering the first login, ten minutes in,
forty-five minutes in, and the first return on a later day. `first-page` is R0g:
scheduled on purpose, because a new player's first characterful moment otherwise
waits on a tally, a record or a place score, and they have none of those.

**Two bugs that only a real firing would have caught**, both in the arrival arc,
both found by writing the test rather than reading the file:

- Two of the rules used an `"all": [...]` shorthand the condition parser does
  not have. They loaded, validated, evaluated false forever, and would have
  shipped as four lines nobody ever heard.
- `first-return` as first written said *"you have been here before, on an
  earlier day"*, which is true of every returning player for the rest of time.
  Only `once` stood between a four-hundred-session veteran and being greeted
  with "page two".

A third was structural: `first-page` and `first-hour` both sit on the session
clock, and a rule that loses an evaluation pass has **still had its rising edge
consumed by it**. An overlap is not a line that arrives late, it is a line that
never arrives. The windows were made mutually exclusive rather than trusted to
arrive in order.

**R2 and R3 — shipped 2026-08-11.** `pickPhrase` drew uniformly at random,
refusing only an immediate repeat, which sounds right and is not: simulated
against the shipped rules, `idle-chatter` began repeating after 8.5 draws with
thirty variants written. A shuffle bag makes that thirty. R3 came out much
narrower than written — `noTwoRulesShareASentence` already forbids two rules
owning the same line, so the ping-pong it was aimed at cannot arise; what
remains is carrying the bag's guarantee across a refill.

This also makes **R4 worth doing, which it was not before**. A thirty-first line
for `idle-chatter` bought almost nothing under uniform selection.

**R19 — shipped 2026-08-12.** Most of its letter had already arrived as
errands (fire, altar, bank, cat, the studies); what was genuinely missing was
the *noticing*. The explore errand walks to plain curiosities - a chest, a
trapdoor, a noticeboard - and reacts to that thing by name, and alongside the
schedule it has an arrival trigger: entering a new region arms a watch, and
once the player holds still the follower goes for its look around, rationed
by a cooldown so a trip through four regions is one inspection.

**R17 — shipped 2026-08-12.** Per-line say counts, persisted with the rest of
the memory and counted at delivery rather than at the win (a line the queue
dropped has not worn out its welcome). Retirement lives in the shuffle bag's
refill: lines said twenty times stand aside while anything fresher exists in
the same rule, and when every line is equally worn they all come back,
because wear is relative and retiring the lot would retire the rule. The
ledger is capped at 500 entries, least-worn dropped first.

**R16 — shipped 2026-08-12.** As cheap as promised: era-gated siblings on the
recurring touchpoints. The login hello and the level-up each split on
daysKnown - formal on days 0-2, thawing 3-6, the familiar register from day
7 - with the formality carried by the grammar (full sentences, no
contractions) rather than announced. A lint holds the eras to tiling the
calendar exactly, so no day is silent and no day has two hellos racing.

**R20 — shipped 2026-08-12.** A "Memory..." button on the side panel opens the
surface: the date it met you, sessions, mood, the top counts, the bests, the
incident, place feelings with region ids left visible (it doubles as the
debugging window), the rolled tastes, what it is carrying or hoping for right
now, and the bookkeeping. Underneath, "Forget everything" - one confirmed
click unsets every stored blob and rebuilds the context blank, so the
follower meets you again as a stranger, arrival arc unspent. The snapshot is
taken on the client thread; the claims are a pure function held to honesty
in both directions by tests.

**R24 — shipped 2026-08-12.** Every rule that opens a demanding interaction -
asks, want, wish, challenge - now carries none(repeating). The idle gates
already kept most openers out of the grind itself; the guard covers the
seams: the pause at the bank mid-session, and the combat grind the challenge
used to interrupt freely. The lint is field-driven, so a future opener
cannot arrive unguarded.

**R23 — shipped 2026-08-12.** The first region of each rolled list is core:
feelsAbout answers from the roll there whatever the earned score says. No
blob change - the lists were already ordered - and the memory window names
the two places under "Won't be argued out of".

**R4 — shipped 2026-08-12.** The soak model (three seeds, ten simulated
hours) ranked rules by firings; the twelve loudest observations with the
thinnest lists gained 33 lines between them - deaths, the combat edges,
examines, level-ups in both eras, the standing bets, the disliked places and
the AFK watch. Jokes stayed thin on purpose, per R4's own qualifier.

**R6 — shipped 2026-08-12.** The boundary condition: a six-second breather
window opened whenever something ENDS - combat, a level, an arrival,
thieving, and the bank, watched by widget presence. Opened after the dispatch
that caused it, so a boundary rule rises on the next pass instead of
competing with the ending's own reaction. First tenant: the bank breather.
The ambient migration happens rule by rule as transcripts show what lands
mid-task.

**R25 — shipped 2026-08-12.** Stay has a life: a staying condition marking
only the PLAYER-commanded Stay (the plugin distinguishes it from errand,
spectate and thrall stays), taking the post acknowledged, the sentry mutter
paced by chance-flicker and cooldown, the notes coming out on the verified
scroll pair, and the reunion - leaving the parked follower's sight and
coming back - noticed as a boundary.

**R18 — shipped 2026-08-12.** The idle pool swaps by context: a Wilderness
set (clipped, watchful, via a new inWilderness state condition), a crowd set
(people-watching, eight players within ten tiles), and the default
everywhere else - partitioned by guards the way the voice eras partition the
calendar, exactly one pool serving in any state, behaviourally tested in all
three.

**R28 — shipped 2026-08-13.** The voice document, half a page as specified:
docs/voice.md. Dry and unexplained, one thought per line, contract
everything with the era exception named as a deliberate device, the notebook
as the character, and the mechanical rules listed beside the lints that
enforce them. **R21 ships inside it** as doctrine: one remembered fact per
line, framed warm, the number's warmth over the number.

**R26 — shipped 2026-08-13.** wonders-about-you: ten idle lines that turn
the attention around, written under the new voice doc as its first proof.
Deliberately no asks field - a real answer would make it a form, and the
not-needing-one is the warmth. Questions claim nothing, so R7's
verifiability rule is satisfied by construction; the trail-off throttles it
like all idle speech.

**Still open**: R5, R7, R8, R11–R15, R22, R29–R37.

**What five rounds of testing found**, including two shipped bugs that lived
between features rather than inside them, and why overlapping systems are
harder to verify than separate ones: [testing-notes.md](testing-notes.md).
