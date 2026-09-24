# The chronicle's voice

The chronicle is the same scribe as the phrase file, writing instead of
talking. [voice.md](voice.md) still governs everything it says; this is how
that voice holds up over a page. Use it to write new pages and to check old
ones. The short form is the "On the page" section of voice.md.

The rules came from reading what makes a written bestiary, travel journal or
codex a pleasure to read: Darwin's Beagle diary, Gilbert White, Marco Polo,
Herodotus, Audubon, Grinnell's field notes, Leonardo's notebooks, the Varrock
Museum's plaques and Reldo's library in the game itself, and the codices of
Hollow Knight, Outer Wilds, The Witcher 3, Subnautica, Bastion, Fallen London
and Cultist Simulator. Each one was then checked against voice.md,
[follower-character.md](follower-character.md) and the window's own
measurements, so a rule that sounded good but fought the character was
dropped or bent to fit.

The one idea under all of it: **the scribe is fascinated, and it never says
so.** The fascination is in what it chooses to look at, how long it stays,
and what it bothers to write down. A scribe who calls things fascinating is
a brochure. A scribe who spends a paragraph on a potion's name is in love
with the world.

Each rule gives what it says, why, one line to copy, one to avoid, and how
to check it. The checks are for a reviewer reading the page. The ones a test
already enforces are marked; the rest are by hand until someone builds the
lint.

## The musts

Four things are on every page, whatever else it does:

1. It is true at the moment the page opens (rule 2).
2. Its sources are marked: seen, told, or read, and by whom (rule 3).
3. It fits the window (rule 14).
4. A creature or place page has its one watched thing (rule 1).

Everything else is a rotation (rule 16). A page uses three or four of the
devices below, never all of them.

## The rules

### 1. The wrong thing, watched

**Rule.** Build every creature and place page round one thing the scribe
watched for itself. It has to be true in the game, belong to this subject and
no other, and be stated plainly with a first-person verb. By an adventurer's
standards it is usually the wrong thing: the masonry, the potion's name, what
the creature does when nobody is fighting it. The fascination shows in how
long the page stays on it, never in an adjective. Tie it to a named place only
when the unlock guarantees the scribe was there.

**Why.** follower-character.md already gives the scribe this bias: more
interested in the masonry than the dragon. Gilbert White gave a common bird
the same patient attention as a rare one, and the Natural History Museum
credits that attention, not the subject, for why his book lasted. Bastion's
narrator earns his place by saying what the player could not have known.
Failbetter tells its writers to stay with the character's senses and what is
observable to them. In Gielinor, Varmen's notes on the pottery puzzle are the
model: one person, one puzzle, looked at for days.

**Do.** When it came up beside us, the lantern went out. I wrote that down
first and was frightened second.

**Don't.** The Giant Mole is a truly fascinating creature. Level 230, three
tiles across, and a fairly simple fight as these things go.

**Check.** Every enemy, boss and location page has a first-person witnessed
verb outside quotes (I watched, I saw, I noticed, I stood, I waited, I
counted, I tried). None of fascinating, remarkable, amazing, incredible,
marvellous or "truly" appears in prose. The reviewer can name the watched
thing in five words, confirm the game shows it, and confirm no other page's
subject could carry it.

### 2. Write to the moment the page opens

**Rule.** A page says only what is true at the earliest moment it can open,
which the header's unlock line names. A creature page opened by sight proves
you met one, not that you fought it or did its quest. A `questBegun` page is
written from the quest's first conversation: what you were told and what can
be seen, never what you later did there. If a page can't be written that way,
change its unlock to `quest` (finished), which is a one-field data change. Write
no "Later." or "since" until the code can know that later happened.

**Why.** `ChronicleProgress.isReadable` opens a `questBegun` page as soon as
the quest is in progress, so those pages can be read straight after the first
conversation. This is voice.md's "claim only what the follower can see"
applied to paper, and it is about being correct rather than about suspense:
research on spoilers finds they rarely spoil enjoyment, but a page that knows
what you haven't done yet is simply wrong. The Outer Wilds ship log keeps what
you heard apart from what you found, and Grinnell's field notes were written
at the moment of seeing, not tidied up afterwards.

**Do.** (Baxtorian Falls, readable from Almera's first word) Almera says her
boy went down the river after treasure. He can't swim, and he's already
fallen in three times. I have a page on the falls and nothing yet on what's
under them.

**Don't.** "...until the monks of Zamorak tricked you into killing it during
Priest in Peril." That can be read the moment King Roald sends you out,
before there is a dog, a monk or a trick.

**Check.** Read each page as it stands at its earliest unlock. On
`questBegun` pages, refuse "you killed", "you took", "you freed", "tricked
you", "you slipped" and "had you". On pages unlocked by sight, flag any
sentence that puts a quest's name next to "you". Refuse "Later." and " since "
in page prose until threshold code exists.

### 3. Seen, told, read, and by whom

**Rule.** Mark where each claim comes from, and write each kind its own way.
What the scribe saw is stated flat. What someone told it is credited by name.
What nobody alive saw is credited to a named source in the world (Reldo, a
book on his shelves, a Varrock Museum plaque, Historian Minas) and keeps that
source's register. "The histories" means Reldo's collection and appears at
most once a page. "The notes on" a topic is refused, because it is the wiki
in a hat. Never hedge anything the adventurer acts on: levels, places, steps.

**Why.** Marco Polo's prologue promises to set down things seen as seen and
things heard as heard. Gielinor's own librarian works the same way: Reldo's
foreword to Lores and Histories admits some of his texts may not be accurate
and keeps them anyway, for want of better. The museum's plaques say what a
thing "is thought to have been". The palace library has real titles to cite,
among them the Shield of Arrav book, the goblin symbol book and the Varrock
census.

**Do.** (The Seven's page, open once the quest is done) Drezel says the Seven
blessed the river from this spot. There are seven monuments round the well
below. I didn't go close, and I lowered my voice anyway.

**Don't.** "The notes on Silvarea add that the warriors rest under the
temple." Also: "The bridge is said to be the only crossing, perhaps." (A hedge
on a fact the adventurer uses.)

**Check.** Refuse "the notes on" in prose, and allow "histories" once a page.
Flag any paragraph that names an Age but has no source word (says, said,
told, has it, according to, Reldo, museum, plaque, or a book title). Every
event before the adventurer arrived is credited to someone or something in the
world, and no level, place or step carries a hedge.

### 4. Understatement does the marvelling

**Rule.** Wonder shows as understatement, as notes that fall short, or as the
scribe going quiet, never as adjectives or exclamation marks. The dairy cow
gets the same attention as the King Black Dragon. The whole book gets one
sentence where words openly fail, saved for something as big as the first
dragon. Lists stay sober, with at most one entry that breaks. Grave pages drop
contractions, shorten their sentences, and aim any lightness at the scribe
itself, never at the dead.

**Why.** Darwin's diary calls "delight" too weak a word for his first
Brazilian forest; the wonder is in the word falling short.
Humboldt warned against poetic prose in natural history. The museum's natural
historian says of the dragon only that no ancestor can be found, and moves on.
The goblin symbol book lists the tribes soberly and ends on Yurkolgokh, The
Flatulent. voice.md: never enthusiastic in more than one word.
follower-character.md section 4: the grave register drops contractions.

**Do.** Four kinds of breath, they say, from three heads, and I haven't worked
out which head does which. Three pages on it so far, and none of them right.
On a grave page: "He died in the attempt. I have no remark for that one."

**Don't.** The King Black Dragon is a majestic, awe-inspiring beast, truly one
of the most breathtaking sights in all of Gielinor!

**Check.** No "!" outside quoted speech. None of majestic, breathtaking,
awe-inspiring, awesome, funny, hilarious or joke in prose. Grave pages
(faction-seven-priestly-warriors, enemy-temple-guardian, and the history
paragraphs of world-morytania) have no contractions. At most one breaking
entry per list, and at most one words-fail sentence in the whole book.

### 5. Doubt once, correct inside the visit

**Rule.** The scribe may openly doubt one claim per page by setting a
reputation beside what it saw. When it was wrong, a "Correction:" line says
so, leaves the first line standing, and is stated as fact, never as an
apology. It may be wrong about motives, inner workings and first impressions,
and never about levels, places, drops, steps or what the adventurer did.
Until the book can date its notes, a correction happens inside the visit the
page records, not "later".

**Why.** Herodotus doubts more often than he believes, and says so; he saw
the phoenix only in a painting. The Witcher 3's drowner entry corrects a folk
belief with gills and fins. voice.md keeps "it gets things slightly wrong" as
a quirk, and calls wrongness about what the player can check a bug. A doubt
on a number anyone can look up is the bug kind.

**Do.** Goblins are said to be stupid. The ones I watched argued very well;
they just argued about the wrong things. And, on the Waterfall page:
"Correction: I had Hudon down as lost. He isn't lost. He's stuck, and he
prefers it."

**Don't.** "I suspect the King Black Dragon is weaker than they say; level
150 at most, by my eye." (A doubt bent onto a number anyone can check.) Also:
"Sorry, I had that wrong earlier."

**Check.** Every level, place, drop and step is checked against the OSRS wiki
before it ships. At most one doubt marker per page (I doubt, I don't believe,
I'd have said, says otherwise). "Sorry" and "apolog" never appear in prose
outside quotes, and "Correction:" appears at most once a page.

### 6. Leave one gap, mostly unasked

**Rule.** Each creature and place page names one thing the scribe can't
account for and stops there. Usually that is a flat statement ("nobody has
told me why") or a "To ask:" line naming someone in the world. Only sometimes
is it a question, because this scribe rarely asks and a question should
matter when it does. The page never answers its own gap, and a later page
that does refers back to it. Never hold back what the reader needs to follow
the page, and never go near the thing the scribe itself keeps back.

**Why.** follower-character.md measures questions at about 3 percent of the
follower's lines: it rarely asks, so asking means something. Leonardo's
notebooks are full of errands to himself that name who to ask. Subnautica
plants a fact about a small fish and pays it off hours later. Tolkien argued
that some distant places should stay unvisited, because going there spends the
magic. follower-character.md section 7 says what the scribe holds back is an
asset.

**Do.** They call Bandos the Big High War God, and say he brought them here.
Why a war god wanted soldiers of this size, nobody has told me.

**Don't.** "The Temple Guardian was a dog because the Saradominists bred
beasts like it as guardians." (Answers its own gap, and states lore as fact.)
Also: "Why green? Why two generals? Why Bandos?" (Three questions on one
page.)

**Check.** At most one "?" per page outside quoted speech, and fewer than half
the pages in the book carry one. Flag a creature or place page with no gap
marker ("?", "To ask", "nobody has told me", "I don't know", "I haven't found
out"). The gap isn't answered further down the same page.

### 7. One line about you, and it drifts

**Rule.** A page carries at most one line that belongs to the adventurer
rather than the subject. It is warm, in the Considered register, claims only
what the unlock guarantees, and is never a death count. What it knows depends
on where the page falls in an account's life. Pages most people open in their
first hours (the goblin, Lumbridge, Cook's Assistant) know nothing of you yet
and may say so, or leave the line out. Pages opened late may hold one fact.
It shows the drift and never names it, so no page says the book is really
about you.

**Why.** follower-character.md section 2: early on it mostly catalogues the
world because it doesn't know you yet, and the arc is that drift. A page is
static, so it reads the same on day 1 and day 300; a line about you on every
page would put the late friendship on the first pages anyone opens. Hollow
Knight's Hunter writes about himself only once you've killed enough of a
thing, which is the same drift done by numbers. voice.md: one remembered fact
per line.

**Do.** Early, on the goblin page: "Most people's first fight is a goblin, and
I expect yours was. I wasn't there, so I've left a space." Late, on the King
Black Dragon: "You pulled the lever to go and see it. What I thought while you
did, I've kept to myself."

**Don't.** "You have killed 214 goblins, died to them twice, and first met one
on the 3rd of Pentember in bronze." (A dossier, a death count, and a date the
memory doesn't keep.) Also: "Between us, this book was never really about
goblins."

**Check.** At most one sentence per page is about the adventurer as a person,
and it claims nothing beyond the unlock. On first-hours pages it admits
ignorance or is absent.

### 8. The notebook shows its making

**Rule.** The book's best lines are its own workings: what the scribe
underlined, took minutes of, put the quill down for, crossed out, checked
twice or left a space for. Use this once or twice a page, always as a
reaction to something on that page, and rotate which one appears. Never
promise workings the page doesn't deliver.

**Why.** voice.md: the notebook is the character. The lines a reader
remembers from the first pages are all workings: "Drevil" written down with a
question mark, the minutes taken very quietly, the quill put down. Varmen's
notes are dated by the in-world day and each one picks up the last one's
puzzle. Grinnell cross-referenced his journal, species accounts and catalogue
by date and place. Alexis Kennedy's answer to the dull codex was footnotes
worth reading for their own sake.

**Do.** I wrote "Drevil" down with a question mark, and nobody consulted the
notes.

**Don't.** "Each of those gets its own footnote." (There are no footnotes.)
Also "Underlined." as a paragraph of its own, for the third time in the book.

**Check.** Count workings words per page (underlin, margin, minutes,
footnote, quill, ink, crossed out, left a space) and allow one or two. Flag
any of them used on more than three pages in the book. Each use reacts to
something on the same page, and any promise (a footnote, a page of its own)
is kept.

### 9. Tell it once, point after

**Rule.** Every story has one home page. Any other page gives it one sentence
and sends the reader there, as the monk page does with "The dog has its own
page.", and dates, numbers and names on two pages must match. A creature or
place page names one to three other pages in its prose; a quest page may name
the pages it passes through. Each is named once, with a reason to go. The
prose ends on a lead that starts from something the reader already knows,
never a see-also list.

**Why.** Wikipedia's summary style puts the full treatment of a subtopic in
its own article and a pointer everywhere else. Grinnell gathered every
sighting of a species in one account. On Outer Wilds, Alex Beachum found that
a character's small story about a place is what makes players want to go
there. A story told at length on four pages is read four times and believed
less each time, especially when two of them disagree on the date.

**Do.** (On the Priest in Peril page) Paterdomus stands over the Seven. They
have their own page, where I wrote their names out in full.

**Don't.** A quest page's "Worth knowing" retelling the Seven, the war,
Hallowvale, Drakan's siblings and the founding of Canifis, when each has or
will have its own page.

**Check.** Count other pages' names in each page's prose, and on creature and
place pages flag none or more than three. Flag any sentence of 12 or more
words that repeats on another page, or twice on one page. Every date that
appears on two pages is the same, and no paragraph retells a story whose home
is elsewhere.

### 10. What happened to us, not how to do it

**Rule.** A quest's "What happened" keeps a step only if something happened
at it or the scribe reacted to it. Directions go to "Where it took us" and the
places list. Bare numbers (levels, rewards, experience, requirements, drop
odds) go to the details list as the scribe's ledger, so no fact is lost. On
creature pages, how to fight is told as something watched, never as advice: "a
corner it cannot reach you in" is a safespot in a cloak. "Where it is found"
is the exception and is directions by design, because a locked page shows it
alone.

**Why.** Alexis Kennedy's case against the codex is that it explains
everything; the better page trusts the reader to miss what they don't need.
The Witcher 3's in-game drowner text explains what the creature is, and the
fighting advice lives somewhere else, written by someone else. A reward table
in the middle of a story stops the story.

**Do.** The flour took longest. You tipped the grain in at the top of the
mill, pulled the lever, and went all the way down to find it had got there
first. Millie explains the mill to anyone who asks. It's a lever.

**Don't.** "Then he let you use his range. ... Rewards: 1 quest point, 300
Cooking experience and the use of the Cook-o-matic 100." (A reward table
mid-story.) Also: "Most of those spots have a corner it cannot reach you in."
(Advice.)

**Check.** In prose only, with lists exempt, flag hitpoints, experience,
quest point, tiles, Slayer, Diary task and "cannot reach you". Quest prose
stays at or under about 5,000 characters. Every step left in "What happened"
has an event or a reaction attached, and every fact cut from the prose now
sits in "Where it took us" or a list.

### 11. Measure it yourself, in Gielinor's units

**Rule.** Most creature and place pages, though not every one, carry one
number the scribe worked out itself, with the method showing and in its own
units: strides, a candle's length, a cartload, a count given up halfway. Size
belongs here, as a comparison, because the plate can't show it. Drop odds are
never "by my tallies", since one adventurer's tallies can't show a one in
5,000. They go in the details list as words ("rare", "rarer still") or
credited to someone in the world.

**Why.** Darwin timed a tortoise at about four miles a day, allowing time for
it to eat on the way. Audubon tried to count a passing flock of pigeons with
pencil dots, gave up, and counted the dots instead. Gielinor's own examine
texts compare rather than count. voice.md says the follower doesn't know drop
rates. `ChroniclePortraits` sizes every model to fill its plate, so scale has
to be said in words.

**Do.** Nose to tail, about the length of a hay cart. I paced it out while it
was busy ignoring us.

**Don't.** "Rune scimitar, about one in a hundred and twenty-eight by my
tallies." (A rate no single adventurer's tallies could show.)

**Check.** "By my tallies" never shares a sentence or list item with "one
in". Most creature and place pages carry one number the scribe made, with its
method, and scale is given in prose wherever it matters.

### 12. Open on a voice, not a verdict

**Rule.** The summary's first sentence is something someone said, something
that happened in front of the scribe, the first thing it noticed, or the story
in the name. The classification comes second. The world's own description may
be quoted as what people say, but only as one opener among several: "described
as X" followed by a two-word verdict ("Understated.") is retired as a house
formula. Never open on "One of the", "A type of" or "A kind of".

**Why.** The museum's natural historian opens each talk on an epithet, not a
category. The Witcher 3's drowner entry opens on what people wrongly believe.
An opening that classifies is the first thing a reader skips, and the summary
is the only text on screen when a page opens.

**Do.** "An ugly green creature" is the common verdict, and no goblin I've met
has troubled to dispute it. They dispute everything else.

**Don't.** "One of the most common low-level creatures in Gielinor, and the
first thing most new adventurers ever fight." Also: "Described as a very large
elemental adversary. Understated."

**Check.** The summary's first sentence is under about 120 characters and
doesn't start with "One of the", "A type of" or "A kind of". "Described"
appears on no more than a third of pages. No summary or section opening
repeats another page's opening clause.

### 13. Squeamish, and would deny it

**Rule.** The scribe is squeamish about violence and would deny it, so fights
are written from their edges: before, after, where it stood, what it looked at
instead. It never describes a blow landing or a thing dying in its own sight,
and a kill is recorded in the fewest words the page allows. Deaths in history
are stated plainly and credited, in the period's own words, never in a modern
idiom.

**Why.** follower-character.md section 6 gives the scribe this trait, and the
code already makes it a watcher: creature pages open on sight, not on a kill.
Hollow Knight's Hunter learns a creature by killing enough of it; this scribe
learns it by looking, which is the difference between the two books. Gilbert
White watched living things for the joy of understanding them.

**Do.** (Temple Guardian, once the page opens on the quest being done) It was
over quickly. I looked at the ceiling of the mausoleum instead, which is well
made.

**Don't.** "You cut it down in three blows and it bled out on the steps."
Also: "He made a suicide run at Drakan." (A modern phrase on the page whose
register most needs to hold.)

**Check.** Refuse "suicide", "bled", "gore" and "gut" in prose, and flag a
page with more than one kill word in the scribe's own voice. No sentence shows
a blow landing or a death in the scribe's sight, and violence in history is
credited.

### 14. Fit the window

**Rule.** The page column is 256 pixels: about 45 characters a line and 14
lines a screen, and the plate takes nine of those lines. That leaves about
five lines of summary when a page opens, so keep the summary to about 220
characters. Keep paragraphs to 150 to 270 characters, with 360 as the
ceiling. Allow at most one single-sentence paragraph a page, and open each
paragraph with its topic. Only one section per page runs long, and only if it
holds the payoff, because length itself tells a reader where the answers are.

**Why.** Measured in `ChronicleLayout`: text from x 214 to 470, 14 pixel
lines, a body from y 103 to 307, and eight plate rows plus a caption line.
Kelsey Beachum on Outer Wilds: the longer texts were where the answers went,
and players learned to read length as a signal. Failbetter caps each part of
a storylet at a set number of words.

**Do.** A mole the size of a hay cart, under Falador Park. Wyson the gardener
grew it by accident, with a potion whose name should have warned him. It puts
candles out.

**Don't.** A 440 character summary that ends on the quest's last four steps.

**Check.** Summary at most about 220 characters, no paragraph over 360, at
most one single-sentence paragraph per page, and prose at most about 1,900
characters for a creature or place and 5,000 for a quest.

### 15. Plates carry shape, never scale

**Rule.** The portrait carries shape and colour, so the prose never spends
words on heads, colour or silhouette. It can't carry scale, because every
model is framed to fill the plate, so size belongs to the prose. Each caption
is one line in the scribe's hand: the subject plus one thing the picture can't
show (how it was drawn, from where, when, or what the scribe thinks). It is
never a bare label, never a caption another plate uses and never a repeat of a
spoken line. The prose mentions the drawing at most once a page.

**Why.** Bastion's narrator never describes what the player can already see.
Alexis Kennedy: a game shows, a codex tells. `ChroniclePortraits.fit` sets
the camera so the model fills the plate at every angle, which makes a goblin
and a dragon the same size. The Varrock Museum pairs each plaque with the
thing in its case.

**Do.** "Drawn from life. Briefly." "Drevil, as he nearly was." "The falls.
The ink ran."

**Don't.** "Lumbridge." under a sketch of Lumbridge. Also a summary that opens
"A black dragon with three heads" above a turning portrait of a black dragon
with three heads.

**Check.** Tested: "Plate VIII. " plus the caption fits the plate's width
minus 32 pixels in p11 (`everyPageHasPlatesThatCanBeDrawn`), and no caption
is one word, the page's own name, or used on another plate
(`everyCaptionIsItsOwn`). By hand: no prose sentence restates what the plate
shows, and no caption repeats a line the follower says aloud.

### 16. Musts and a rotation

**Rule.** The four musts are on every page. Everything else rotates: the gap,
the doubt, the measurement, the line about you, the workings, a tangent that
comes back, one pun, a mention of the drawing, a closing line. A page uses
three or four of them, not all. No stock phrase or device appears on more than
about a third of the pages, and no page closes the way the page before it in
the list does.

**Why.** voice.md tests every change by asking whether it makes the follower
more correct or more predictable, and only the first is welcome. Nine devices
packed into every page stops being a voice and becomes a form; "described" on
page after page is how a recipe hardens into a tic. voice.md also keeps "it
goes quiet in odd places", so a page with nothing to add may add nothing.
Failbetter's "points of light, pools of shadow" makes the case for leaving
most of a scene dark.

**Do.** The goblin page closes on a gap ("Why a war god wanted soldiers of
this size, nobody has told me."). The hill giant page after it closes on a
doubt instead.

**Don't.** Every creature page ending on a "To ask:" line, every summary
opening on a quoted description, and a margin note on every page.

**Check.** Across the book, flag any device marker ("described", "To ask",
"Correction:", "underlin", "minutes", "which tells you", "I am fond", "by my")
used on more than a third of pages. Read the pages in list order and check
that neighbours close differently.

## The shape of a page

The window is 500 by 314. The page column shows 14 body lines, and the plate
and caption take 9 of them, so about 5 summary lines show when a page opens.
`ChronicleLayout.page` fixes the order: plate and caption, summary, headed
sections, then the people, places and details lists, headed People,
Places and Particulars in the window.

- **Summary.** At most about 220 characters, in two or three sentences.
  Sentence one is the opener (rule 12). Sentence two says what the subject is.
  An optional third gives the scribe's stance. It stands alone, and it is true
  at the earliest moment the page opens.
- **Creature and boss pages** (What it is, Where it is found, Worth knowing):
  about 1,300 to 1,900 characters of prose.
  - *What it is*: two paragraphs. What it does when left alone, the watched
    thing, and its size by comparison. Mechanics only as things watched.
  - *Where it is found*: one paragraph of about 250 characters, the nearest
    one or two places with a detail each. A locked page shows only this, so it
    is directions by design.
  - *Worth knowing*: two or three paragraphs. One credited story, with a
    pointer to its home page if it has one, then three or four devices from the
    rotation, ending on a lead to another page.
- **Location pages** use the same headings. "What it is" is the arrival: where
  the scribe stood, what it lowered its voice for, what it paid or ate. The
  history goes in "Worth knowing", credited by name. End on something the
  scribe wants, such as seeing the place by night, not on a summary.
- **Quest pages** (What happened, Who was there, Where it took us, Worth
  knowing): aim for 4,000 characters of prose, with 5,000 as the ceiling.
  - *What happened*: three to five paragraphs, each opening on its topic ("The
    egg first."). Events and reactions, not step order.
  - *Who was there*: a short paragraph for each person who mattered. Optional
    helpers go in the people list only.
  - *Where it took us*: the directions live here, one line per place.
  - *Worth knowing*: may be the page's one long section if the payoff is
    there. A story with a home elsewhere gets one sentence and a pointer.
- **Faction and world pages**: about 1,400 to 2,000 characters. Start with
  what the scribe itself saw (the well, the river, the coffins), credit the
  rest, and use the grave register where the subject calls for it.
- **Lists are the ledger**: levels, drops (as words or credited), immunities,
  rewards, requirements and the full map. One item per line, about 90
  characters at most so it wraps to two lines or fewer. Creature and place
  lists stay short (people 1 to 5, places up to 6, details 3 to 7). Quest
  lists may run longer, because facts cut from the prose land there and every
  fact stays. At most one dry entry per list.
- **Paragraphs**: 150 to 270 characters, ceiling 360. Each opens with its
  topic and each sentence does one job.
- **Furniture, used rarely**: "To ask:" lines; "Correction:" lines, inside the
  visit; a margin note on the conditions of writing (the spray, the dark) on
  perhaps one page in five; and a closing line where the page wants an ending
  ("That's all I have on goblins, for now."), varied, never a formula.

## Pictures

Every page opens with a plate, and a test holds it to that. A creature or
person is its own game model, turning, framed to fill the plate. A place is a
watercolour the scribe makes the first time you stand still in one of its
regions, with you in the middle. Before then, a portrait shows as an ink
outline captioned "Not yet seen." and a sketch as bare paper captioned "I'll
draw it when we're there." That already matches the research: Audubon's
plates marked as drawn from nature, the medieval bestiary artist working from
a description, and the museum case marked as having no information yet.

- **What the plate can and can't carry.** It carries shape, colour and
  silhouette, so the prose never describes them. It can't carry scale, sound,
  smell, behaviour, history or opinion, so the prose spends its words there.
  The caption joke about scale is used twice already ("To scale, roughly.",
  "Bigger than it looks here."), so don't use it again.
- **Order.** Plate I is the subject: the creature, the place, or, for a quest,
  the person who set it going. Later plates show things the text talks about
  that the reader can't see yet: the keeper, the rival, the impostor, the place
  where it turned. Use two to four plates a page, and mention every plate after
  the first in the prose, so the words and the pictures point at each other.
- **Places get several views.** File each view under its own sketch name with
  its own regions (the square, the palace end, the Exchange), so the
  adventurer's own walk fills the page. A region is 64 tiles square, so a
  caption has to be true wherever in it the scribe happened to draw. Check
  every region and NPC id against the cache before it ships (`runAudit` for
  regions, `runNpcFind` for NPCs); a wrong one fails silently and the scribe
  simply never draws the place. Every portrait also carries its NPC's
  standing animation as `idle`, which `runChronicleAudit` prints and checks.
- **Captions.** One line, about 30 characters. The subject plus one thing the
  picture can't show:
  - how it was drawn: "Drawn from life. Briefly."
  - from where: "From the far bank."
  - when: "Spots, before we knew."
  - what the scribe thinks: "Bentnoze, disagreeing."

  Never a bare label, never a caption another plate uses, and never a line the
  follower already says aloud (the sketch line "You're in it, for scale."
  rules out a "You, for scale." caption). Captions follow rule 2, so a caption
  on a `questBegun` page gives away nothing the quest hasn't reached. On grave
  pages captions stay plain: "The well. I stood back."
- **The drawing as the scribe's work.** At most once a page, the prose may
  mention the drawing and how it was made ("The spray got into the ink.", "My
  sketch flatters it."). That counts as the page's workings for rule 8.

## Not until the code exists

Pages are static. The book now knows when and where each page was written
(from the day dates were added; older pages are undated), but nothing else
about the time since. So none of these get written until the code for them is
built, and none of them get built unasked:

- "Later." notes at thresholds a player actually reaches (about 100 for a
  common enemy, 25 for a boss, 5 visits for a place).
- Rumour text on locked pages.
- Hearsay captions for unseen plates, such as "From Wyson's account." in place
  of "Not yet seen.".
- Tally placeholders in page text.
- Dated notes within a page, such as a "Correction:" added on a later visit.
- An item plate, for quests whose key thing is an object (Glarial's urn, the
  Cook-o-matic).

## Where this meets voice.md

Some voice.md rules were written for spoken lines. On the page they settle
like this:

- **Contractions.** Reportage and credited history may stay uncontracted,
  because the book is a written hand. The scribe's asides, doubts and the
  line about you contract, as it speaks. Grave pages have none at all, since
  dropping contractions is the only way the grave register shows on paper. An
  ordinary page with no contractions anywhere has lost the voice.
- **One thought per line.** The line rules govern the page's small units: the
  summary's first sentence, captions, list items, "To ask:" and "Correction:"
  lines. Paragraphs run two to four sentences, each sentence doing one job,
  and voice.md's "cut the first half" test applies to every paragraph.
- **Never enthusiastic.** Fascination is attention, not volume (rules 1 and
  4). The book's one words-fail sentence is its version of "PET!" earning its
  capitals once.
- **It rarely asks.** Gaps are mostly stated flat (rule 6).
- **The arc is the drift.** The line about you follows where the page falls in
  an account's life (rule 7).
- **Numbers.** Numbers about the world are the job. A number about the
  adventurer appears alone, only in the line about you, and is never a death
  count.
- **The project is never admitted.** The line about you is about the
  adventurer, never about why the book exists.
- **It gets things slightly wrong.** In its own head, yes (rule 5). About
  what the player can check, never. Drop odds "by my tallies" are the wrong
  kind of wrong, because the scribe couldn't have measured them.
- **Never apologetic.** A correction is a scholar's pride: stated as fact, the
  first line left standing, no "sorry".
- **Claim only what it can see.** A lore sentence with no source is a bug, the
  same way an unverifiable spoken line is (rules 2 and 3).

## Open questions for the owner

- Do pure route facts (the palace bucket, 2 coins at the general store, the
  southernmost bookcase) count as facts that must stay, or as walkthrough the
  book may drop?
- The locked-page hint speaks of "The scribe" in the third person ("The
  scribe has not met one of these yet.") in a book otherwise written in the
  first person.
- Locked creature pages show "Where it is found" but not the places list. If
  the full map is wanted there, that is a small code change.

## Where the first pages stand

Measured on 2026-09-24, after the redundancy pass that cut the repeats out
of the 17 pages. That pass fixed what was redundant and nothing else: the
retellings became pointers, the lists became a ledger, the Seven's date now
agrees, "by my tallies", "suicide run" and the unkept footnote promise are
gone, the spoilers went from the pages that open when a quest starts, and
no paragraph passes 360 characters. What is left to bring in line:

- **Rule 10.** Cook's Assistant runs about 4,500 characters of prose, Priest
  in Peril about 5,200 and Waterfall Quest about 9,200, against a 5,000
  ceiling. What remains is the story and its history, not repetition.
  Priest in Peril still gives a safespot as advice ("a spot between the pews
  he cannot reach you in").
- **Rule 12.** "Described" is used 12 times on 7 pages, and "One of the" is
  still in the King Black Dragon's prose.
- **Rules 1 and 16.** The goblin, fire giant, monk and moss guardian pages
  have no first-person "I". The fire giant page is still the weakest in the
  book; the Giant Mole page is the best.
- **Voice.** The pages carry no contractions at all.
- **Rule 14.** Summaries run 110 to 226 characters; three pass 220 by a few.
- **Rule 3.** Some history paragraphs, on the Waterfall and goblin pages
  among them, have no source in the world.
