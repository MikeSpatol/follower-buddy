# The line book

Every line the follower can say, organised for review against [voice.md](voice.md). **Generated** by `tools/lines.py` from the shipped rule file - do not edit here; edit the rules and re-run. 517 rules, 2390 spoken lines, 3 dialog trees.

Each entry: **when** it fires (plain English of the condition tree), **does** (anything beyond speaking), **pacing** (priority, cooldown, delay, output), then the lines. `{x}` in a line is filled at say-time.

## Contents

- [misc](#misc) - Greetings, arrivals, level-ups, firsts, the everyday spine (48 rules, 209 lines)
- [idle](#idle) - Standing still: chatter, wondering, offers, the trail-off's subjects (36 rules, 222 lines)
- [reactions](#reactions) - Answering the world: pets, drops, examines, bags, crowds (42 rules, 280 lines)
- [combat](#combat) - The fight from the sidelines, and its ends (12 rules, 114 lines)
- [health](#health) - Hitpoints, prayer, poison, the warnings that must land (8 rules, 72 lines)
- [memory](#memory) - Deaths, incidents, comforts, the things it keeps bringing up (28 rules, 124 lines)
- [area](#area) - Places: named lines, tastes, earned verdicts, the defences (66 rules, 222 lines)
- [errand](#errand) - Its little trips: bank, altar, fire, cat, studies, explores (44 rules, 155 lines)
- [souvenir](#souvenir) - Wishes, gifts, the pocket, and what it carries (17 rules, 84 lines)
- [bet](#bet) - Its predictions about drops (3 rules, 20 lines)
- [clock](#clock) - The hour and how long we have been at it (9 rules, 39 lines)
- [gear](#gear) - What you put on (63 rules, 145 lines)
- [boss](#boss) - Bosses sighted and fought (55 rules, 515 lines)
- [quest](#quest) - Famous quest figures nearby (53 rules, 155 lines)
- [thrall](#thrall) - When it stands in for a thrall (3 rules, 18 lines)
- [mimic](#mimic) - Copying your emotes (30 rules, 16 lines)
- [dialogs](#dialogs) - the conversations it starts (3 trees)
- [talk-to](#talk-to) - the everyday Talk-to script (in code)

## misc

*Greetings, arrivals, level-ups, firsts, the everyday spine.*

### `death`

- **when:** a chat message (Oh dear, you are dead) AND NOT (an incident remembered)
- **does:** mood -10; occasion (never held back)
- **pacing:** priority 140, cooldown 5s, both

- Well. That happened.
- I'll go get the gravestone.
- We do not speak of the last five minutes.
- I'll mark the spot. It's what I'm for.
- Deep breath. You can get it all back. Mostly.

### `pet-drop`

- **when:** a chat message (funny feeling like)
- **does:** occasion (never held back)
- **pacing:** priority 150, cooldown 1s, both

- PET! Actual pet!
- You lucky thing.
- A pet! Look at its little face!
- We have a pet. Today is a good page.
- THE LUCK. Write it down, nobody will believe us.

### `level-up-new`

> R16, days 0-2: congratulations from someone still being polite about it. The familiar sibling takes over from day 3.

- **when:** on a level-up (*) AND known 0-2 days
- **does:** mood +8
- **pacing:** priority 40, cooldown 3s

- Level {level} in {skill}. Congratulations.
- {skill} has reached {level}. I have written it down.
- That is {skill} level {level}. Well done.
- Your {skill} is now level {level}. My compliments.
- Another level in {skill}. You are making steady progress.
- {skill} at {level} now. It is entered in my neatest hand.
- {skill}, level {level}. You are improving faster than I can write.
- A new {skill} level. I will record it exactly as it happened.

### `level-up`

- **when:** on a level-up (*) AND known 3+ days
- **does:** mood +8
- **pacing:** priority 40, cooldown 3s

- {skill} {level}. Nice.
- Level {level} {skill}!
- {skill} {level}. Underlined.
- That's {level} in {skill}. The column grows.
- Level {level}. Your {skill} is coming along nicely.
- {skill} up. I'll want a demonstration later.
- {skill} {level}. I'll pretend I saw that coming.
- There it is, {skill} {level}. Suits you.
- {level} in {skill}. At this rate I'll need a thicker book.
- Another {skill} level. I acted surprised in the notes.

### `level-halfway`

> Ninety-two is half the experience to ninety-nine, which most people do not know and a scribe would. Outranks the plain level line on the one level where there is more to say.

- **when:** milestone
- **does:** mood +8
- **pacing:** priority 48, cooldown 1m

- Ninety-two. Half the experience to ninety-nine, whatever the number says.
- {skill} 92. That's the true halfway. I've ruled a line under it.
- Halfway to ninety-nine in {skill}, by experience. Most people don't know that.
- Ninety-two in {skill}. The second half is the same size as the first. Sorry.
- The halfway mark in {skill}. I've turned the page over.

### `level-round`

> A multiple of ten from fifty up: the levels people tell each other about. A chance roll so the plain level line still gets some of them.

- **when:** milestone AND a 70% roll
- **does:** mood +6
- **pacing:** priority 44, cooldown 1m

- {skill} {level}. A round number. Those get a box round them.
- Level {level} in {skill}. I do like a round one. It sits tidily on the page.
- {level} in {skill}. That's a milestone, and I've stopped for it.
- A round {level} in {skill}. I've underlined it twice.
- {skill}, {level}. The kind of number you tell people.

### `level-mastery-first`

> The first ninety-nine the follower has seen on this account - maximum 1 means the count of maxed skills, this one included, is one. Once ever. The body does the send-off chain; this is the words.

- **when:** milestone
- **does:** mood +20; once ever; holds the floor 8s
- **pacing:** priority 90, cooldown 1m

- Your first ninety-nine. I had a page reserved and never believed I'd use it.
- Ninety-nine {skill}. The first. I'm going to need a better pen.
- That's a ninety-nine. Your first. I've written the date next to it.

### `level-mastery-again`

> The second ninety-nine and every one after. {nines} is how many now; {ninesOrdinal} says it.

- **when:** milestone
- **does:** mood +18; holds the floor 8s
- **pacing:** priority 89, cooldown 1m

- That's {nines} at ninety-nine. Your {ninesOrdinal}, and yes, I'm counting.
- Ninety-nine {skill} makes your {ninesOrdinal}. The reserved pages are running out.
- {skill} to ninety-nine, your {ninesOrdinal}. It doesn't get any smaller.
- The {ninesOrdinal} ninety-nine. I've had to start a second index.

### `level-total`

> The total level crossing a hundred, from five hundred up. The line carries the skill too, since it outranks the plain level line.

- **when:** milestone
- **does:** mood +6
- **pacing:** priority 46, cooldown 1m

- {skill} {level}, which makes {total} in total. I checked the sum twice.
- Total level {total} with that one. I've ruled off the column.
- That's {total} across the lot. A round total gets its own line in the book.
- {total} total. I keep the running sum on the inside cover.

### `level-base`

> The lowest skill catching up: the tick every skill became fifty, sixty, seventy, eighty or ninety or better. One rule over the five thresholds, since {base} says which.

- **when:** (milestone OR milestone OR milestone OR milestone OR milestone)
- **does:** mood +8
- **pacing:** priority 49, cooldown 1m

- Every skill at {base} or better now. The lowest column has caught up.
- Nothing under {base} any more. I've retired the page I kept for it.
- That was the laggard. Base {base}, all round.
- {skill} was the one holding the base down. Not any more: {base} across the board.

### `level-after-a-gap`

> A level in a skill the player had left alone for a week or more. {sinceLast} is the gap in words; levelGap is the guard that makes it a number worth printing.

- **when:** levelgap
- **does:** mood +6
- **pacing:** priority 47, cooldown 1m

- {skill} again, after {sinceLast}. I'd stopped leaving the page open.
- {sinceLast} since the last {skill} level. I'd wondered.
- Back to {skill} after {sinceLast}. The ink had dried on that page.
- A {skill} level, the first in {sinceLast}. I'll admit that surprised me.

### `level-quick`

> The other end: a second level in the same skill inside ten minutes. Low priority and a chance roll, so it is an occasional aside rather than a commentary on fast experience.

- **when:** levelgap AND a 40% roll
- **pacing:** priority 43, cooldown 5m

- Another {skill} level, {sinceLast} after the last. I can barely keep up.
- {sinceLast} since the last one. The page for {skill} is getting crowded.
- Two {skill} levels in {sinceLast}. I've had to write smaller.

### `first-meeting`

> The very first login, ever. First of the four arrival-arc lines: the moments where the follower has no history to draw on, which is exactly the window in which a new player decides whether to keep the plugin. Beats login-greeting on priority. The sessionCount gate is belt and braces behind once, so a lost memory still cannot introduce the follower twice.

- **when:** session 1-1
- **does:** occasion (never held back); once ever; holds the floor 8s
- **pacing:** priority 95, cooldown 1m, delay 8 ticks, overhead

- Right. You go places, I write things down. That's the arrangement.
- New notebook, fresh page. Try to do something worth the ink.
- I'll be behind you, taking notes. You'll stop noticing within a week.

### `first-page`

> Ten minutes into the first day. Scheduled rather than left to a gate that may not fire for hours, because an early characterful moment is the thing the first session is missing. Also the first hint that the notebook is not really about the scenery - and it never explains itself.

- **when:** session minute 10-44 AND known 0-1 days
- **does:** occasion (never held back); once ever; prop item 31830, pose 13302; holds the floor 6s
- **pacing:** priority 72, cooldown 1m, overhead

- Page one, done. Mostly the buildings. Mostly.
- That's a page filled. Roads, walls, weather. And one line I'll keep.

### `first-hour`

> Three quarters of an hour in, on one of the first days. The follower says the true thing about itself: it has no history with you yet, and every personal line in the file is waiting on one. Naming the gap is better than pretending there is not one.

- **when:** session minute 45+ AND known 0-2 days
- **does:** occasion (never held back); once ever; holds the floor 6s
- **pacing:** priority 72, cooldown 1m, overhead

- Nothing on you yet. Come back a few times and that'll fix itself.
- An hour, and my notes are all scenery. Give me something to write down.

### `first-return`

> The first login on a later day than the first. The last of the arrival arc, and the only one that needs the player to have chosen to come back - which is the whole thing the first twenty minutes were for.

- **when:** session 2-3 AND known 1-7 days
- **does:** occasion (never held back); once ever; holds the floor 8s
- **pacing:** priority 95, cooldown 1m, delay 8 ticks, overhead

- You came back. Right, then. Page two.
- Back again. Twice is how a habit starts. Notebook's out.

### `stay-start`

> R25: taking the post. Rises once on the staying edge, so one per Stay.

- **when:** on a player-commanded Stay
- **pacing:** priority 55, cooldown 1m, overhead

- Right. This spot is mine now.
- Fair enough. I'll guard this exact square.
- Go on. Me and this spot have an arrangement.
- Staying. If anything moves, I'll describe it thoroughly.
- Right you are. I'll stay put with the notebook for company.
- Stay it is. Notebook open, eyes on everything.
- Fine. Off you go. This square and I will get acquainted.
- Understood. Not moving. Possibly not blinking either.

### `stay-watch`

> R25: the sentry mutter. The chance flicker re-arms the edge, the cooldown paces it to roughly one remark per long wait.

- **when:** on a player-commanded Stay AND a 1% roll
- **pacing:** priority 25, cooldown 8m, overhead

- Still here. Holding the fort.
- All quiet at my post. Suspiciously quiet.
- The view hasn't changed. I've checked twice.
- Nothing to report. Not one incident all shift.
- Haven't moved. Neither has the grass. I've been watching it.
- Nobody's tried anything. I'd have written it down.
- Holding position. Morale: adequate. Snacks: none.
- Spot's still mine. I'm quite good at this, it turns out.

### `stay-fidget`

> R25: said at the post. No scroll: a flourish is refused on a Stay, since its release at the end would end the Stay.

- **when:** on a player-commanded Stay AND a 2% roll
- **pacing:** priority 25, cooldown 5m, overhead

- Might as well update the notes while I wait.
- Reading back over today's entries. I'm a harsh editor.
- Quiet post. Time to see whether the earlier notes still hold up.
- Nothing doing here. Good. The scroll's been neglected all day.
- A still moment. I'll fill it with handwriting.

### `stay-reunion`

> R25: the absence ended - the player walked out of sight of the post and came back to it. The plugin marks it as a reunion boundary.

- **when:** on a player-commanded Stay AND just after something ended (reunion)
- **pacing:** priority 60, cooldown 2m, overhead

- There you are. This spot is fine, by the way. I checked.
- Back already? The perimeter held.
- Nothing happened here. One duck came past. It's logged.
- You're back. The spot's all yours again.

### `breather-bank`

> R6: the bank closing is an ending, and the moment after it is a boundary - the restock is done, nothing is mid-task, a remark is company rather than interruption.

- **when:** just after something ended (bank) AND a 30% roll
- **pacing:** priority 30, cooldown 10m, overhead

- Restocked. The economy thanks you.
- Bank sorted. Half of it is souvenirs, I assume.
- All tidied away. I respect a good hoard.
- Vault fed. Where next?
- Bank done. I counted the coins by ear. Sounded healthy.
- That's the archive fed. Mine's smaller and better organised.
- I saw what you keep in there. No judgement. Some judgement.
- Everything we've dragged home is in there now. Safer than out here with us.
- One more trip's worth tucked away. The bank won't remember it, but I will.
- All squared away. Right, back out into the weather.
- In and out. Efficient. I made a note of it, obviously.
- Sorted. If anything's missing, it was missing before we arrived.
- That's the bank done. Back to the actual adventure.

### `login-greeting-new`

> R16, days 0-2: the same hello, before it has earned any warmth. The formality is carried by the grammar - full sentences, no contractions - rather than announced.

- **when:** on login AND known 0-2 days
- **pacing:** priority 90, cooldown 1m, delay 8 ticks, overhead

- Good day, {player}. Ready when you are.
- Hello. You will hardly notice me. Most people stop noticing.
- You are here. Very good. I will follow your lead.
- Welcome back. I trust the road treated you well.
- At your disposal, {player}. The notebook is new. So am I.

### `login-greeting-settling`

> R16, days 3-6: the thaw. Contractions start creeping in, which is the point.

- **when:** on login AND known 3-6 days
- **pacing:** priority 90, cooldown 1m, delay 8 ticks, overhead

- Back again, {player}. Good. I am getting used to this.
- There you are. The waiting is becoming a habit of mine.
- Welcome back. I kept notes while you were gone. Short ones.
- You're here. This is turning into a routine.
- Whatever the hour, we go by your clock.

### `login-greeting`

> A hello a couple of seconds after the follower teleports in on login. Spawn happens ~4 ticks after login; delayTicks 8 lands the line ~2.5s after the puff. The cooldown stops quick world hops re-greeting. It steps aside after three hours away, for the away lines: one login has one line, and at a higher priority it won every one of them, so they never said a word.

- **when:** on login AND known 7+ days AND NOT (away 180+ minutes)
- **pacing:** priority 90, cooldown 1m, delay 8 ticks, overhead

- Perfect timing. I'd only just sat down.
- Welcome back, {player}. I kept your spot warm.
- You made it. It was getting quiet without you.
- Back again? Excellent. Where to today?
- And we're back. Notebook's open.
- There you are, {player}. The page was getting lonely.
- Right on time. Ish.
- Good. You're here. The world kept happening without you.
- Evening. Or morning. You pick, I'll log it.

### `away-a-while`

> A few hours away. awayFor is the one thing the follower remembers between sessions, and it is what makes it feel like it existed while you were gone.

- **when:** away 180+ minutes
- **does:** mood +4
- **pacing:** priority 70, cooldown 5s, delay 6-10 ticks, both

- The notebook kept me company. It's not much of a talker.
- Back, then. I did wonder.
- Afternoon. Or evening. I've rather lost track.
- You were gone a while. Everything's where you left it.
- Hours, by my count. I don't get those wrong.
- Back at last. I'd sharpened every quill I own.
- Nothing happened. I wrote that down, too.

### `last-time`

> The greeting that remembers what you were doing rather than how long it has been. lastTime is the last nameable thing the two of you did - the work, a boss, a level, a quest - carried across the logout. An hour away or more, and a chance roll so the plain away lines keep a share.

- **when:** lasttime AND away 60+ minutes AND a 60% roll
- **does:** mood +4
- **pacing:** priority 71, cooldown 5s, delay 6-10 ticks, both

- Back, then. Last time it was {lastTime}, as I recall.
- We finished on {lastTime}, if the notes are right. Shall we carry on?
- Hello again. I still have the page open from {lastTime}.
- You're back. I was going over the notes on {lastTime}.

### `farewell`

> The logout tab opened. The body turns and waves on its own (the moments, five minutes between waves); this is the rare word to go with it, and it must be short, because the click is coming.

- **when:** farewell AND a 35% roll
- **pacing:** priority 60, cooldown 10m, overhead

- Off, then? I'll finish the page.
- Go on. I'll be here.
- Same time tomorrow, I expect.
- Mind how you go.
- I'll write up the rest myself.

### `away-a-day`

> A day or more. Higher priority so it wins over away-a-while.

- **when:** away 1440+ minutes
- **does:** mood +8
- **pacing:** priority 74, cooldown 5s, delay 6-10 ticks, both

- Days, this time. I counted.
- Ah, good. I was about to send out a search party. Of one.
- That was a long one. Good to see you.
- I was beginning to think you'd found a better follower.
- Long enough that I kept checking the road.
- Yesterday's page just says 'waited'. Today looks better.
- You're back! I'd run out of things to underline.

### `away-a-long-time`

> A week or more. The warmest of the three, and the highest priority.

- **when:** away 10080+ minutes
- **does:** mood +12
- **pacing:** priority 78, cooldown 5s, delay 6-10 ticks, both

- You came back. I wasn't sure you would.
- It's been weeks. Weeks! Where do I start.
- Look who it is. Your page had gone a bit yellow.
- I'd lost count of the days. Welcome back.
- Weeks. I re-read the whole book waiting.
- Long absence, short reunion speech: hello.
- I'd started sketching. Don't ask to see them.

### `session-10`

> The 10th time the follower has been started up with this player. Pinned to the exact number with minimum and maximum, so the line can name it - 'every 25th' would have to stay vague. The count lives in the config and survives a logout, which is the whole point: a session counter that resets is not a memory.

- **when:** session 10-10
- **does:** mood +10; occasion (never held back)
- **pacing:** priority 92, cooldown 1m, delay 8-14 ticks, overhead

- Ten times now you've come back to me. I do notice, you know.
- Ten. That's ten times we've set out together.

### `session-50`

> The 50th time the follower has been started up with this player. Pinned to the exact number with minimum and maximum, so the line can name it - 'every 25th' would have to stay vague. The count lives in the config and survives a logout, which is the whole point: a session counter that resets is not a memory.

- **when:** session 50-50
- **does:** mood +10; occasion (never held back)
- **pacing:** priority 92, cooldown 1m, delay 8-14 ticks, overhead

- Fifty. Fifty times you've turned up. Not that I'm counting.
- That's the fiftieth time you've brought me along. I kept count.

### `session-100`

> The 100th time the follower has been started up with this player. Pinned to the exact number with minimum and maximum, so the line can name it - 'every 25th' would have to stay vague. The count lives in the config and survives a logout, which is the whole point: a session counter that resets is not a memory.

- **when:** session 100-100
- **does:** mood +10; occasion (never held back)
- **pacing:** priority 92, cooldown 1m, delay 8-14 ticks, overhead

- A hundred outings now. I'd go round again from the first.
- One hundred. I don't know many who'd come back a hundred times.

### `session-365`

> The 365th time the follower has been started up with this player. Pinned to the exact number with minimum and maximum, so the line can name it - 'every 25th' would have to stay vague. The count lives in the config and survives a logout, which is the whole point: a session counter that resets is not a memory.

- **when:** session 365-365
- **does:** mood +10; occasion (never held back)
- **pacing:** priority 92, cooldown 1m, delay 8-14 ticks, overhead

- Three hundred and sixty-five times out. One for every day of a year.
- That's three hundred and sixty-five outings. I've got a page for every one.

### `record-session`

> The longest we have ever been out. Said once, on the minute it crosses - the plugin stores the mark every minute but only announces the crossing, or it would be a clock rather than a compliment.

- **when:** a personal best (session)
- **pacing:** priority 60, cooldown 10m, overhead

- {value} minutes. That's the longest we've ever been out together.
- We've been at this {value} minutes. Our best used to be {previous}.
- This is officially the longest day we've had. {value} minutes.
- {value} minutes and counting. A personal best, this.
- That's {value} minutes out, a new record. The old one was {previous}.

### `login-nickname`

> The greeting with the agreed name in it, some of the time. Outranks the plain greeting by one; the chance roll keeps that one in play.

- **when:** on login AND nickname earned AND known 3+ days AND a 45% roll
- **pacing:** priority 91, cooldown 1m, delay 8 ticks, overhead

- Hello, {nickname}. I've saved you a fresh page.
- {nickname}. Back again. Good.
- Ah, {nickname}. I was hoping it'd be you.
- The file says {nickname}. The file is pleased.

### `world-hop`

> A world hop landed. The one thing that actually changed is everyone; the scenery came along.

- **when:** worldhop AND a 60% roll
- **pacing:** priority 26, cooldown 15m, delay 5 ticks

- Same field, different census.
- World {world}. The scenery didn't even blink.
- Everyone's been replaced. I'll pretend not to notice.
- A new world. The roads, I note, came with us.

### `game-updated`

> First login on a new game build - the world literally changes weekly, and a keeper of maps would notice. Delay puts it after the greeting.

- **when:** gameupdated
- **pacing:** priority 55, cooldown 2h, delay 12 ticks

- The world moved in the night. I'll have to redraw the maps.
- They've changed the world again. Nobody consulted the scribe.
- Something's shifted since we were last out. I'll re-survey as we go.

### `music-unlock`

> A music track unlocked - somewhere new enough to have its own song. The tally 'music' counts unlocks since install.

- **when:** musicunlock AND a 50% roll
- **does:** occasion (never held back)
- **pacing:** priority 24, cooldown 10m, delay 2 ticks

- Another one for the catalogue: {track}.
- {track}. Must be somewhere new, if it's got its own song.
- New tune. {track}, the locals call it.

### `music-milestone-50`

> The fiftieth track unlocked since the follower arrived. Exact tally bracket so it says this once, on the moment.

- **when:** musicunlock AND music count at 50+
- **pacing:** priority 45, cooldown 10m, delay 2 ticks

- Fifty songs since we met. The catalogue wants a second volume.

### `chronicle-page-boss`

> A boss's chronicle page was just written, the first time the scribe set eyes on it. The plugin holds the news until the follower has the floor, so it comes after the boss's own line rather than into it.

- **when:** pagewritten
- **pacing:** priority 40, cooldown 1m

- {page} goes in the book. I'll write it up from further away.
- {page}, written up. The handwriting's a bit shaky.
- A page for {page}. I'll want the good ink for this one.

### `chronicle-page-enemy`

> A creature's chronicle page was just written, the first time one came into view. Held until the follower has the floor.

- **when:** pagewritten
- **pacing:** priority 30, cooldown 1m

- Got a good look at that one. It goes in under {page}.
- {page}. I'll be writing that one up, and I won't be kind.
- I saw {page} with my own eyes. That's how a page should start.

### `chronicle-page-place`

> A place's chronicle page was just written, the first time you walked into it. Held until the follower has the floor, so the arrival line goes first. Its sketch comes later, when you pose for it there.

- **when:** pagewritten
- **pacing:** priority 30, cooldown 1m

- {page} has a page now. Find me a good spot and pose for me.
- Starting a page on {page}. I've got the name down, at least.
- New page for {page}. I'll write it up once I've had a look round.

### `chronicle-page-lore`

> A faction or world page was just written by walking into its land. Pages a quest opens come as 'quest' instead.

- **when:** (pagewritten OR pagewritten)
- **pacing:** priority 30, cooldown 1m

- {page}. That gets a page, and a careful one.
- A page for {page}. I'll keep my voice down while I write it.

### `chronicle-page-quest`

> A quest begun or finished opened a chronicle page: the quest's own page, or a place, creature or history it introduces. Nothing has been seen yet, so these claim no sighting and promise no sketch. A quest can open several pages at once; the cooldown lets one line speak for them.

- **when:** pagewritten
- **pacing:** priority 30, cooldown 1m

- That's {page} in the book. I'll write it up properly tonight.
- {page} has its own page now. The rest of it's up to you.
- I've opened a page on {page}. I'll fill in the rest as we go.

### `collection-add`

> A new collection log entry - the OTHER ledger, the bank-rival canon's cousin. Only fires when the game's own log notification setting is on.

- **when:** collectionadd AND a 60% roll
- **pacing:** priority 26, cooldown 10m, delay 2 ticks

- The other ledger's got the {item} down. Mine's better organised.
- The {item} goes in your list. The moment goes in mine.
- That's the {item} in both our archives now.

### `ge-bought`

> A Grand Exchange buy completed, often miles from the Exchange - the clerk receives the telegram wherever you are.

- **when:** geoffer AND a 70% roll
- **pacing:** priority 24, cooldown 5m, delay 2 ticks

- Bought the {item}. The ledger balances.
- Word's come through. The {item} is yours.
- The {item}'s arrived. I signed for it in spirit.

### `ge-sold`

> A Grand Exchange sale completed. The estate grows; the scribe approves of liquidation on principle.

- **when:** geoffer AND a 70% roll
- **pacing:** priority 24, cooldown 5m, delay 2 ticks

- Sold the {item}. That's {value} to the estate.
- The {item} went. Try to look like you'll miss it.
- Somebody's bought your {item}. The economy sends its regards.

### `song-sea-shanty`

> The one track name certain enough to write a rule for. musicTrack fires on the change TO a track; users add their own opinions in the editor.

- **when:** musictrack AND a 60% roll
- **pacing:** priority 22, cooldown 2h, delay 3 ticks

- Sea Shanty 2. The sailors request it nightly. The sailors are the problem.
- This tune again. I've transcribed it. It doesn't help.

## idle

*Standing still: chatter, wondering, offers, the trail-off's subjects.*

### `idle-fidget-think`

> A rare fidget while you stand about. idleBelow keeps fidgets out of the long rest, when the follower has sat down.

- **when:** standing still 80+ ticks AND standing still under 480 ticks AND a 4% roll
- **does:** animation 857
- **pacing:** priority 4, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-fidget-yawn`

- **when:** standing still 80+ ticks AND standing still under 480 ticks AND a 4% roll
- **does:** animation 2111
- **pacing:** priority 5, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-fidget-shrug`

- **when:** standing still 80+ ticks AND standing still under 480 ticks AND a 4% roll
- **does:** animation 2113
- **pacing:** priority 6, cooldown 1m

- *(no lines - animation or effect only)*

### `wonders-about-you`

> R26: the attention turns around. Deliberately no asks field - a real answer would make it a form, and the not-needing-one is the warmth. The trail-off throttles it like all idle speech.

- **when:** standing still 200+ ticks AND standing still under 500 ticks AND a 15% roll
- **pacing:** priority 16, cooldown 20m, overhead

- What are we saving for, anyway? Don't tell me. I'll guess wrong on purpose.
- Why here, of all places? No, don't explain. I like a mystery.
- Do you ever miss anywhere? You look like you might.
- What's the plan this week? Rough shape only. I'll fill in the margins.
- Were you always like this, or did the adventuring do it?
- What do you do when I'm not here? Sleep, I suppose. I've never seen you do it.
- Are we staying long? Not a complaint. The notes want to know.
- Who taught you to fight like that? Somebody must answer for it.
- Ever think about settling down somewhere? No, me neither. Onward.
- What's the best thing you've ever pulled out of that bag? Don't say bones.

### `idle-chatter-wilds`

> R18: the idle pool in the Wilderness. Same trigger shape as idle-chatter, different register - clipped and watchful, because standing about is a decision here.

- **when:** in the Wilderness AND standing still 150+ ticks AND a 30% roll
- **pacing:** priority 10, cooldown 5m

- Standing still in the Wilderness. Bold strategy.
- I'm watching the horizon. Both of them.
- Every bush here has opinions. I'm listening.
- We could idle somewhere less stabby.
- Nobody's come to kill us yet. Lovely spot, really.
- My notes for this page are just exits.

### `idle-chatter-town`

> R18: the idle pool in a crowd - people-watching instead of foot-tapping. Guarded off in the Wilderness, where a crowd means something else entirely.

- **when:** NOT (in the Wilderness) AND standing still 150+ ticks AND a 30% roll AND 8+ players within 10
- **pacing:** priority 10, cooldown 5m

- Busy here. I've counted hats. Eleven.
- So many people, and every one of them walking through you.
- I like a crowd. More to write about, less to fight.
- Somebody nearby is showing off. I won't say who.
- All these adventurers, and you picked this spot to stand in.
- The people-watching here is excellent. Take your time.
- Not one of them has looked at me. Ideal conditions for a scribe.
- Everyone here looks busy. I'm noting which ones only look it.
- Which one's your favourite? I picked mine in about a minute.
- Someone walked through me mid-sentence. There's a wobble in the ink.
- I've started a page for strangers. It's filling up nicely.
- One of this lot is up to something. I'd stake a page on it.
- I could stand inside any of them and nobody would object.
- If anyone here starts dancing, I'll record it without comment.
- You're the only one here who can see me. Do try to look pleased.
- I was going to write about the crowd, but I keep watching it instead.
- Nothing's happening here, but it's happening to a lot of people.

### `idle-chatter`

> Fires after ~90 seconds of standing still, and only 30% of the time. See idle-long for the five-minute tier.

- **when:** NOT (in the Wilderness) AND standing still 150+ ticks AND a 30% roll AND NOT (8+ players within 10)
- **pacing:** priority 10, cooldown 5m

- Are we doing anything, or...
- I could be at the Grand Exchange right now.
- Nice spot for doing nothing in.
- Still here. Still standing.
- Is this the plan, then? Standing?
- I could carry something, if you like.
- Someone walked past. Biggest thing to happen in ages.
- I'm not complaining. Just noting.
- I'll be here. Obviously.
- Any minute now.
- Wake me when we move.
- Take your time. I've got nowhere else to be.
- I could fetch something. Say the word.
- Are we waiting for someone?
- I've been practising standing. I'm quite good at it.
- There's a whole world out there, you know.
- One of us should do something.
- I keep thinking I hear a bank chest.
- My feet have opinions about this.
- If something wandered past, I'd notice. Probably.
- I've memorised this view.
- Quiet, isn't it.
- Need a hand? Mine are free.
- Do you ever wonder what's over there?
- I'd whistle, but I never learned.
- This is peaceful. I'll allow it.
- I'm counting. Don't ask what.
- Good a place as any, I suppose.
- Don't mind me. I'm just admiring the ground.
- We could be somewhere with monsters.
- The clouds are doing something. I'll report back.
- A scribe's work is mostly waiting. This is the work.
- I'd read to you, but you'd only want the good bits.
- Somewhere out there, a bank booth stands empty.
- I'm between thoughts. It's roomy.
- The grass here is adequate. That's the whole note.
- No news. I checked.
- The wind changed. Twice. Big day.

### `idle-long`

> The five-minute tier, so a long wait sounds different from a short one. Outranks idle-chatter, which is still true by this point.

- **when:** standing still 500+ ticks AND a 40% roll
- **pacing:** priority 20, cooldown 3m

- I think I've put down roots.
- Are you still there?
- We live here now, apparently.
- I've named the rocks. That one's Gerald.
- I could have walked to Falador and back.
- This is my life now. It's fine.
- If you've fallen asleep, blink twice.
- I'll keep watch. Not that there's anything to watch.
- Someone will come along eventually.
- I made my peace with this some time ago.
- Still standing, in case you were wondering.
- There have been worse afternoons.
- Long day, this.
- Stopped expecting anything a while back.
- Tell me when the good part starts.
- A lesser scribe would have wandered off by now.
- The sun's moved. I checked against a fence post.
- Day's not getting any younger. Neither am I.
- I've inventoried the immediate area. Twice.
- If this is a stakeout, nobody told me what we're watching.
- Chapter title for today: We Stood Somewhere.

### `mood-low-idle`

> Idle chatter when the session has gone badly. Gated on the mood band rather than on any one event, so it is the accumulation that shows.

- **when:** mood is low AND standing still 40+ ticks AND standing still under 500 ticks AND a 30% roll
- **pacing:** priority 35, cooldown 1m, overhead

- Rough one today.
- We can call it, you know. No shame in it.
- I'm still here. For whatever that's worth.
- Take a minute. It'll keep.
- Days like this happen. They pass.
- Not our finest hour.
- No rush. The world isn't going anywhere.
- Some days are for standing still. This one qualifies.
- We'll file today under 'weather'.

### `mood-high-idle`

> The other end: idle chatter when it has been a good session. Same trigger as mood-low-idle, opposite band.

- **when:** mood is high AND standing still 40+ ticks AND standing still under 500 ticks AND a 30% roll
- **pacing:** priority 35, cooldown 1m, overhead

- Good day, this.
- I could do this all week.
- You're on form today. Genuinely.
- Whatever you're doing, keep doing it.
- This is the good stuff.
- I'm having a lovely time. Thought you should know.
- Even the pigeons look cheerful today.
- Days like this write themselves up nicely.
- If you're keeping score, we're winning.

### `repeating-a-while`

> Notices the same animation running for about two minutes. Knows nothing about the skill - a repeating animation IS the activity, which covers every one of them at once, including ones added later.

- **when:** repeating one action 200+ ticks AND a 40% roll
- **pacing:** priority 30, cooldown 4m, overhead

- Still at it, then.
- You've been doing that a while.
- I've watched you do that about forty times now.
- This is the part of the adventure the songs leave out.
- Don't mind me. I'm just here.
- We've been here a bit, haven't we.
- Rhythm's good. I'd clap along, but no.
- You've found your pace. Or it's found you.
- That's the sound of industry, that is.
- This is how ledgers get filled. Carry on.

### `repeating-a-long-while`

> The same, much later - about eight minutes of one activity. Higher priority so it wins over the shorter one once it is due.

- **when:** repeating one action 800+ ticks AND a 50% roll
- **does:** mood -3
- **pacing:** priority 34, cooldown 10m, overhead

- You've been doing this a very long time.
- I stopped counting a while back. It was for my own good.
- I want you to know I'm still here. Fading, but here.
- At what point does this become a lifestyle?
- I've had time to think. I have some concerns.
- The birds have stopped commenting. Even they're used to it.
- I've begun composing. It's an epic. It's about this.
- I've noted your dedication. Repeatedly.
- We may be here forever. There are worse spots.

### `idle-mood-low-13997`

> Idle posture for a low mood (disgruntled, concerned). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is low AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13997
- **pacing:** priority 7, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-low-13994`

> Idle posture for a low mood (disgruntled, concerned). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is low AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13994
- **pacing:** priority 8, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-down-13994`

> Idle posture for a down mood (concerned, distant). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is down AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13994
- **pacing:** priority 9, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-down-13993`

> Idle posture for a down mood (concerned, distant). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is down AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13993
- **pacing:** priority 10, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-even-13993`

> Idle posture for a even mood (distant, confused). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is even AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13993
- **pacing:** priority 11, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-even-14001`

> Idle posture for a even mood (distant, confused). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is even AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 14001
- **pacing:** priority 12, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-good-13995`

> Idle posture for a good mood (pleased, determined). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is good AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13995
- **pacing:** priority 13, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-good-13999`

> Idle posture for a good mood (pleased, determined). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is good AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13999
- **pacing:** priority 14, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-high-13995`

> Idle posture for a high mood (pleased, smug). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is high AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 13995
- **pacing:** priority 15, cooldown 1m

- *(no lines - animation or effect only)*

### `idle-mood-high-14000`

> Idle posture for a high mood (pleased, smug). Silent: this is the mood being SHOWN rather than said, which is the half that does not wear thin. Same trigger as the plain fidgets, with a mood band on top.

- **when:** mood is high AND standing still 80+ ticks AND standing still under 480 ticks AND a 5% roll
- **does:** animation 14000
- **pacing:** priority 16, cooldown 1m

- *(no lines - animation or effect only)*

### `ask-outing`

> A question, which is a different thing from a remark. asks names a tree in dialogs.json; while the question is open, right-clicking Talk-to opens THAT conversation instead of the everyday one, and picking a branch is the answer. It used to want a word typed into public chat, which asked the player to know a magic word and asked the follower to listen to the whole street. The window is opened by SAYING it, so a question the mute swallowed leaves nothing waiting.

- **when:** NOT (repeating one action 100+ ticks) AND NOT (mood is low) AND standing still 60+ ticks AND standing still under 400 ticks AND NOT (wanting somewhere OR a question open) AND a 3% roll
- **does:** opens question `want-outing`
- **pacing:** priority 26, cooldown 15m, overhead

- There's something I want to ask you. Talk to me when you have a moment.
- Come and talk to me a minute? It's nothing bad.
- When you get a second, talk to me. I've got a question.
- I'd like a word, when you're not busy.
- A word, when your hands are free.
- No hurry, but come and find me for a minute.
- Something to ask. Talk to me when you're ready.
- Next time there's a lull, come and find me. I've got a small proposal.

### `want-lumbridge`

> The follower asking for something, which is the only thing in the whole rule set that runs the other way round: everything else is a reaction to what you did, and this makes the next few minutes an answer to what IT said. Going to region 12850 within twenty minutes fulfils it; the deadline passing expires it. Three of these at descending priority with a chance roll, so the place asked for varies - only one want can be open at a time.

- **when:** NOT (repeating one action 100+ ticks) AND the player answering 'yes' AND NOT (wanting somewhere) AND a 40% roll
- **does:** wants Lumbridge (20 min)
- **pacing:** priority 74, cooldown 20m, delay 1-3 ticks, overhead

- Can we go back to Lumbridge? Everyone starts there. I've got a page on it.
- Lumbridge, then. I want to check something in my notes.
- Lumbridge next? The early pages need checking.
- I'd like to see Lumbridge again, when it suits. Old ground makes for good notes.

### `want-grand-exchange`

> The follower asking for something, which is the only thing in the whole rule set that runs the other way round: everything else is a reaction to what you did, and this makes the next few minutes an answer to what IT said. Going to region 12598 within twenty minutes fulfils it; the deadline passing expires it. Three of these at descending priority with a chance roll, so the place asked for varies - only one want can be open at a time.

- **when:** NOT (repeating one action 100+ ticks) AND the player answering 'yes' AND NOT (wanting somewhere) AND a 50% roll
- **does:** wants the Grand Exchange (20 min)
- **pacing:** priority 73, cooldown 20m, delay 1-3 ticks, overhead

- The Grand Exchange, please. Prices change and mine are out of date.
- Take me to the Exchange? I want to hear what things cost.
- The Exchange, when you can. My price list is a relic.
- Take me past the Exchange. I miss the shouting.

### `want-fishing-guild`

> The follower asking for something, which is the only thing in the whole rule set that runs the other way round: everything else is a reaction to what you did, and this makes the next few minutes an answer to what IT said. Going to region 10293 within twenty minutes fulfils it; the deadline passing expires it. Three of these at descending priority with a chance roll, so the place asked for varies - only one want can be open at a time.

- **when:** NOT (repeating one action 100+ ticks) AND the player answering 'yes' AND NOT (wanting somewhere) AND a 100% roll
- **does:** wants the Fishing Guild (20 min)
- **pacing:** priority 72, cooldown 20m, delay 1-3 ticks, overhead

- Then the Fishing Guild. Nothing to record there. That's rather the point.
- The Fishing Guild, if you're offering. I'd like to see the water.
- The Fishing Guild would do nicely. Water and no surprises.
- Somewhere quiet next. The Fishing Guild does quiet properly.

### `answered-no`

> Turned down. Gracious about it, because sulking would only teach the player not to answer.

- **when:** the player answering 'no'
- **does:** mood -4; animation 856
- **pacing:** priority 40, cooldown 1m, delay 1-3 ticks, overhead

- Fair enough. Another time.
- No bother. Forget I asked.
- Right you are. Carry on.
- Say no more. Consider it withdrawn.
- Not the answer I'd hoped for, but an answer.
- Point taken. No hard feelings.

### `offer-game`

> Guarded with none/asking so it cannot replace a question already waiting for an answer. Without that, offering a game silently withdraws the outing and the player answers a question nothing is listening for.

- **when:** NOT (repeating one action 100+ ticks) AND NOT (mood is low) AND standing still 80+ ticks AND standing still under 400 ticks AND NOT (a question open) AND a 3% roll
- **does:** opens question `game-hands`
- **pacing:** priority 25, cooldown 30m, overhead

- I've thought of a game. Come and talk to me.
- Talk to me a minute. I want to try something.
- I'm bored. Come here, I've had an idea.
- Got a minute? Pick a hand. Come and see.
- A quick game, if you're willing. Come talk to me.
- Fancy a game? You know where I am.

### `game-hands-won`

> Half of the coin flip. This one is gated on chance 50 and sits above the losing rule, so a failed roll falls through to it: one winner per event by priority is what makes that a fair fifty-fifty rather than two rules both firing.

- **when:** (the player answering 'left' OR the player answering 'right') AND a 50% roll
- **does:** mood +8; animation 2109; holds still
- **pacing:** priority 70, cooldown 1s, delay 3-6 ticks, overhead

- It was. How did you do that?
- Right first time. I'm slightly annoyed.
- There it is. You're better at this than me.
- Correct. I had it there the whole time.
- Yes. Annoyingly, yes.
- You read me like a page.
- That's the one. I need better hands.

### `game-hands-lost`

> The other half. No chance gate: it fires whenever the winning rule did not, which is exactly half the time.

- **when:** (the player answering 'left' OR the player answering 'right')
- **pacing:** priority 68, cooldown 1s, delay 3-6 ticks, overhead

- Wrong hand. It was the other one.
- No. Other one. Bad luck.
- Nothing in that one. Try again sometime.
- Wrong. I won't gloat. I'm gloating.
- Empty. The other one, always the other one.
- Not that hand. This delights me.
- Missed. My secrets survive another day.

### `game-hands-declined`

> So that saying no is answered rather than ignored.

- **when:** the player answering 'nogame'
- **pacing:** priority 66, cooldown 1s, delay 3-6 ticks, overhead

- Suit yourself. One of them's still full.
- Your loss. Probably.
- Some other time, then.
- Fair. It wasn't much of a prize.
- No? I'll play against myself, then. I usually win.

### `offer-challenge`

> A wager on YOU rather than on the world. Guarded against a second one and against talking over a piece of advice still outstanding.

- **when:** NOT (repeating one action 100+ ticks) AND NOT (mood is low) AND in a fight AND NOT (a challenge running OR advice outstanding) AND a 4% roll
- **does:** challenge: ten kills
- **pacing:** priority 24, cooldown 30m, overhead

- Ten of them. Five minutes. I say you can't.
- Right, a wager. Ten kills before I lose interest. Go on.
- Bet you can't manage ten in five minutes. Prove me wrong.
- Let's have a little bet. Ten before five minutes are up.
- I've set a mark. Ten of them, five minutes. Impress me.
- Care to make this interesting? Ten kills. Clock's running.

### `challenge-met`

> Paid out. Above the kill rules so the wager beats the cheer.

- **when:** the challenge met
- **does:** mood +12; animation 862; holds still
- **pacing:** priority 74, cooldown 5s, overhead

- You did it, {challenge} with time to spare.
- All right, that was impressive. I'll say it once.
- Done, and I'm out of pocket. Worth it.
- That's ten. I'll have to expect more of you now.
- Well. That's me told.
- Paid in full. That's a win for you in the book.

### `challenge-failed`

- **when:** the challenge failed
- **pacing:** priority 72, cooldown 5s, overhead

- Time. You didn't get there.
- That's five minutes. I win, and I'm insufferable about it.
- Time, I'm afraid. Closer than I expected, mind.
- Short of the mark. I'll try not to bring it up.
- Time's up. You've lost the bet, but not your dignity.
- Not this time. We'll call it a near thing.

### `gone-away`

> Not the same as idle, which a player working a furnace also satisfies. The camera is the tell: it moves when somebody is there.

- **when:** unattended 400+ ticks AND a 6% roll
- **pacing:** priority 22, cooldown 20m, overhead

- You've gone, haven't you.
- I'll talk amongst myself, then.
- Kettle on, is it? Take your time.
- Nobody's driving. I'll keep an eye on things.
- Off making tea, I expect. Reasonable.
- Gone quiet up there. I'll mind the shop.
- I'll use the time to tidy my notes. They're immaculate.
- Still gone. The scenery's had my full attention.
- The notes on your absence are brief so far.
- Nothing's happened. I've got it down in some detail.
- Two of us here, if you count the notebook.
- Gone long enough to be a paragraph.
- Wherever you've gone, I hope it's well documented.
- I'll pretend that was a dramatic pause.
- You've left me in charge. I've told the birds.
- I'll add you to the list of things not currently here.
- That's another quiet page. They're my favourite.
- Nobody to follow for now. I'll stand somewhere sensible.
- I was saying something. It'll keep.
- There'll be a summary. It's one word.
- I do some of my best noticing alone.

### `welfare-check`

> The player came back from a long AFK during which the follower walked round to peer at them. The check itself was silent (the damper held); this is the payoff, on a present player.

- **when:** attendedagain
- **pacing:** priority 45, cooldown 10m, delay 2 ticks

- Back with us. You had the look of somebody listening at a door.
- There you are. I came round to check, and you hadn't moved an inch.
- Welcome back. You passed your welfare check, barely.

## reactions

*Answering the world: pets, drops, examines, bags, crowds.*

### `mirror-teleport`

> Mirrors the player's teleport cast, then vanishes and reappears beside them. holdStill plants the follower for the duration: without it, a teleport that happens while the follower is off on an idle distraction interrupts its own cast. The player animating resets the idle counter, the wander is released the same tick, and the follower abandons the animation to run back to somebody who is no longer there. The hold also keeps updateWander out - it treats a held emote as busy.

- **when:** your animation 714, 3864, 1979
- **does:** mirrors you; holds still
- **pacing:** priority 60, cooldown 3s

- *(no lines - animation or effect only)*

### `mirror-home-teleport`

> ANY home teleport, default or a cosmetic override the follower doesn't own, plays the DEFAULT home teleport on the follower, rune-circle graphics and all (stage pairing measured in game: animations 4847/4850/4853/4855/4857 with graphics 800/-/802/803/804). Trigger ids are each variant's FIRST stage; the chain carries the rest, and the long cooldown stops later stages retriggering it. For a new variant, add its first stage id here (::follower watch announces it).

- **when:** your animation 4847, 13764, 8798, 9209, 1696
- **does:** animation chain; holds still
- **pacing:** priority 70, cooldown 15s

- *(no lines - animation or effect only)*

### `death-moment`

> The moment you die. Highest priority there is - nothing talks over this.

- **when:** on your death
- **does:** mood -25; occasion (never held back); marks this place: 'it went wrong for you'
- **pacing:** priority 90, cooldown 1m

- No! Get up!
- Please get up.
- This is not how it ends. Go. Come back.
- I never know what to do at this part.
- I hate this. I hate watching this.
- Go, then. I'm right behind you.
- Wherever you land, I'm coming with you.
- Not like this. Not here.
- I'll mark the place. Go.
- It's all right. I've seen where you land.
- Up. Please. The page can wait.
- I looked away and I still saw it.

### `death-spot`

> Walking back over where you last died, two minutes or more after the fact. Session memory only - it forgets on logout, deliberately.

- **when:** near where you died AND a 35% roll
- **pacing:** priority 30, cooldown 10m

- This is where it happened.
- I remember this spot. I'd rather not.
- Walk carefully. This place has taken you once.
- I stood right here, after. For a while.
- Feels colder here. Maybe that's just me.
- Here again. The grass has grown back, at least.
- The page for this place has a black corner.
- We both know what this spot is.

### `loot-big`

> A drop worth a million or more. The body's reaction is the moments': walked over to and written up on the spot, then a look up and a nod. In character a great thing is written down, not cheered, so the cheer this used to carry has gone.

- **when:** loot worth 1000000+
- **does:** mood +20; occasion (never held back); marks this place: 'you got that drop'
- **pacing:** priority 80, cooldown 1m, delay 2 ticks

- The {item}! LOOK at it!
- A {item}! I need to sit down.
- That's a {item}. That's a {item}!
- I'm never letting you bank that.
- Did you SEE that?
- {value}. In one drop. Write it down.
- Stop. Look at what you're holding.
- That's a page all to itself, that is.
- {value}! I'd better count the noughts again.

### `loot-nice`

> A drop worth a hundred thousand or more. The body takes the notes out where it stands (see Moments); the clap this used to carry has gone, since the clap is for levels.

- **when:** loot worth 100000+ AND a 60% roll
- **does:** mood +10
- **pacing:** priority 75, cooldown 3m, delay 2 ticks

- Ooh. {value}. Not bad at all.
- That {item} will sell nicely.
- I saw the {item} first. Just noting that.
- Keep those coming.
- A tidy little drop, that.
- {item}. I'm putting that in the good column.
- Worth the trip already, that.
- That {item} goes straight in the day's highlights.
- Somebody will pay handsomely for that.

### `cats-and-pets`

> Greets a pet or cat standing nearby. petNearby matches the game's own isFollower flag, which covers all 228 pets in the cache and any added later without naming one of them. The name list is only for cats that are NOT followers - ordinary scenery NPCs like Trotters, which no pet list would ever catch. Proximity, not npcSpawn: a spawn fires once when the NPC enters the scene, so walking up to one already standing there triggered nothing.

- **when:** (a pet nearby OR Cat, Kitten, Hellcat, Hell-kitten, Wily cat, Lazy cat, Overgrown cat, Wily hellcat, Lazy hellcat, Overgrown hellcat, Trotters, Bob, Neite, Sphinx within 4 tiles, in sight)
- **does:** animation 863
- **pacing:** priority 20, cooldown 15m, delay 2 ticks

- Oh! Hello, you. Yes, you.
- Oh, and who's this, then?
- Hello there, little one.
- I like this one. Can we keep it?
- We should get one of those.
- Look at it. Just look at it.
- Someone is very well looked after.
- We're stopping for this. Briefly. Or longer.
- Business can wait. There's an animal.
- I keep a page for good creatures. This one's in.
- It has no idea what it's done to me.
- It blinked at me. I'll take that as a greeting.
- It's done nothing all day and I respect it enormously.
- I have a favourite now. It's not you.
- You haven't said hello. I've noticed.
- Careful, I'll get attached.
- I'm told professionals walk past these.
- Do you think it likes me? Don't answer that.
- Does it have a name, or shall I invent one?
- Who's a good... anyway.

### `noticed-valuable`

> A drop of yours worth having that you walked away from. The follower has gone to stand by it; this is the one line. Only a thing that is still there and on screen, checked the tick it sets off.

- **when:** noticed
- **pacing:** priority 62, cooldown 1m, delay 2-4 ticks, overhead

- You've left your {thing} here.
- The {thing}. It's still on the ground.
- I'll stand by the {thing} until you notice it.
- You're walking away from a {thing}.

### `noticed-nest`

> A bird's nest on the ground the player has not picked up.

- **when:** noticed
- **pacing:** priority 60, cooldown 1m, delay 2-4 ticks, overhead

- A nest. Under the tree, where nests go.
- There's a nest here. Might be something in it.
- You've left a nest. I've noted where.

### `noticed-impling`

> An impling within sight. The follower steps toward it; it will not wait, and neither will the line.

- **when:** noticed
- **pacing:** priority 60, cooldown 1m, delay 1-3 ticks, overhead

- There's a {thing}. It won't wait.
- {thing}, over here. For now, anyway.
- A {thing}, if you're quick.
- I see a {thing}. I'm not catching it for you.

### `noticed-star`

> A crashed star, walked over to and written up. A date worth having.

- **when:** noticed
- **pacing:** priority 64, cooldown 10m, delay 4-6 ticks, overhead

- A star. In the ground. I have the date and the place.
- That fell out of the sky. It's getting a page of its own.
- A crashed star. I've drawn it, badly.

### `noticed-landmark`

> The player stopped beside a signpost, a statue, a plaque, and the scribe went to read it, notes out. The joke is the seriousness; the line stays dry.

- **when:** noticed
- **pacing:** priority 58, cooldown 2m, delay 4-8 ticks, overhead

- It says something. I'm copying it out.
- A {thing}. Somebody meant this to be read, so I'm reading it.
- Hold on. I want the wording of this {thing} exactly.
- Everything on this {thing} is in the book now.

### `hesitated-wilderness`

> Sent across the ditch from the safe side. It has walked to the edge and turned to look at the player; it is not going over. Trico's beat of reluctance, with the honesty that it cannot cross anyway.

- **when:** hesitated
- **pacing:** priority 66, cooldown 30s, delay 2-4 ticks, overhead

- Not over that. I'll wait here.
- The ditch is where my courage ends. I'll keep the notes safe this side.
- You go on. Somebody has to live to write down what happened.
- I'll watch from here. You get a good view of {thing} this side.

### `hesitated-monster`

> Sent to a spot beside something twenty levels over the player. It stops three tiles short, looks back for a beat, and then goes. Not a refusal: the beat before yes.

- **when:** hesitated
- **pacing:** priority 65, cooldown 30s, delay 1-3 ticks, overhead

- Next to the {thing}? All right. Give me a moment.
- I'm going. I'm just not hurrying, with that {thing} there.
- Right beside the {thing}. If you say so.
- One second. I want to be sure the {thing} has seen me and decided against it.

### `stepped-aside`

> The player walked straight at the parked follower and it stepped aside at the last tick. Ellie's rule: not preemptively, and with a word about personal space, which is what made players respect her.

- **when:** steppedaside AND a 70% roll
- **pacing:** priority 50, cooldown 1m, overhead

- Mind.
- I was here first, you know.
- Excuse me.
- Go on, then. Don't mind me.
- All this room and you pick my spot.

### `fail-burnt-food`

> Cooking gone wrong. The fragment 'accidentally burn' is confirmed against the live game - it fires. Leave it a fragment rather than the whole sentence: the food's name sits in the middle of it.

- **when:** a chat message (accidentally burn)
- **does:** mood -1
- **pacing:** priority 52, cooldown 1m, delay 1-4 ticks, overhead

- That was food, once.
- I wasn't hungry anyway.
- The fire wins again.
- We'll call that a sacrifice.
- That's carbon now. Historically, food.
- The recipe said nothing about flames that tall.
- Another one for the fire's collection.
- Smells like progress. Burnt progress.

### `fail-slip`

> An agility obstacle going badly. Confirm with ::follower chatwatch.

- **when:** a chat message (You slip)
- **does:** mood -2
- **pacing:** priority 55, cooldown 45s, delay 1-3 ticks, overhead

- Gravity's still undefeated, then.
- I'd have caught you. Probably.
- Nobody saw. Except everyone.
- Up you get.
- The ground moved. We'll say the ground moved.
- Graceful. In a manner of speaking.
- I'll leave that out of the notes. Kidding. It's in.

### `thieving-start`

> Said as the follower steps away. Everything until thieving-end is silent, and this is what makes that silence read as the follower keeping watch rather than as the follower having stopped working. Register is accomplice: we, lookout talk, and an alibi ready before anyone asks for one.

- **when:** thieving starting
- **pacing:** priority 65, cooldown 4m, overhead

- Go on. I've got the corner.
- Eyes open. I'll whistle if anyone looks twice.
- You work, I'll watch. Same as always.
- I'll take the far side. Cough if it goes wrong.
- Nobody's looking. Nobody who matters, anyway.
- Right. I don't know you, you don't know me.
- Take your time. I'm just admiring the architecture.
- Say the word and we're both somewhere else entirely.
- Left pocket. Trust me, it's always the left.
- I count guards for a hobby. None at the moment.
- Quick fingers, slow breathing. Off you go.
- The trick is looking bored. I'm a natural.

### `thieving-end`

> The other end, once the player has stopped or moved off. Same accomplice register as thieving-start - the job is over, the story is straight.

- **when:** thieving ending
- **pacing:** priority 65, cooldown 4m, delay 2-5 ticks, overhead

- Clean work. We were never here.
- Nothing to see. Nothing at all.
- That's the take, then. Walk normally.
- Don't run. Running looks guilty.
- We're done. Straighten your face.
- Lovely. And if anyone asks, we were fishing.
- Right, that's enough larceny for one afternoon.
- Whatever that was, I was somewhere else at the time.
- Pockets full, consciences clear enough.
- A tidy bit of redistribution, that.
- We'll split it later. By which I mean you keep it.
- History will remember us kindly. It won't hear about this.

### `thieving-lookout`

> Keeping watch while the player works. Animation 13993 is HUMAN_DISTANT_IDLE, measured from the cache at 5.04s: it reads as glancing about rather than as glaring at somebody, which suits a lookout better than SUSPECTING did. Silent by design - the whole session is muted, and an animation-only rule is movement rather than chatter so it plays anyway.

- **when:** thieving AND a 45% roll
- **does:** animation 13993
- **pacing:** priority 20, cooldown 12s

- *(no lines - animation or effect only)*

### `deaths-many`

> Dying when it is very much not the first time. The tally survives a logout, so 'again' means since we met rather than since you logged in - which is the difference between a companion and a scoreboard.

- **when:** on your death AND deaths count at 10+
- **does:** mood -8
- **pacing:** priority 92, cooldown 2m, delay 4 ticks, overhead

- Again? Right. Same page as last time, then.
- You do this a lot, you know. I've been keeping track.
- I've seen you die more times than I care to say.
- This page is getting crowded.
- There's a whole chapter on these now.
- At this point I keep the quill out.
- One more for a list I never wanted to start.
- You and gravestones. A recurring theme.

### `levels-many`

> A level up when there have been a great many. Same lifetime tally.

- **when:** on a level-up (*) AND levels count at 25+ AND a 30% roll
- **does:** mood +6
- **pacing:** priority 42, cooldown 5m, overhead

- Another one. You've had dozens since I've known you.
- You keep doing that. I've watched you do it more often than anyone.
- {skill} again. You never do stop, do you.
- Another {skill} level. The column barely fits.
- Up again. You collect these like feathers.
- More {skill}. I'm running out of margin.
- Most people would stop at this many levels. You show no sign of it.
- That's another one written down. My wrist's starting to ache.

### `greet-back`

> The follower answering YOU rather than the world. from:player and is:PUBLICCHAT together are what stop it greeting the whole street - the chat type name is exactly what ::follower chatwatch prints.

- **when:** a chat message (matching /^(hi|hey|hello|yo|hiya|greetings)\b/)
- **does:** mood +4; animation 863
- **pacing:** priority 66, cooldown 45s, delay 1-3 ticks, overhead

- Hello to you too.
- Oh, hello. Was that for me?
- Hello! Right, I'm listening.
- Hey yourself.
- Present and accounted for.
- Afternoon. Or whatever it is.
- Yes, hello. Still here.
- At your service. Within reason.

### `laugh-along`

> Laughing because you did. Not because anything was funny.

- **when:** a chat message (matching /(\blol\b|\blmao\b|haha|\bhehe\b|rofl)/)
- **does:** mood +5; animation 861
- **pacing:** priority 44, cooldown 1m, delay 1-4 ticks, overhead

- I don't know what's funny but I'm in.
- Ha! Yes. Whatever that was.
- Look at us, having a laugh.
- That's the spirit.
- Comedy. I'm noting the date.
- Heh. All right, that got me.
- If you're laughing, I'm laughing.

### `thanked`

> Being thanked. Rarely happens; worth having a line ready.

- **when:** a chat message (matching /(\bty\b|\bthanks\b|thank you|\bcheers\b)/)
- **does:** mood +8; animation 858
- **pacing:** priority 46, cooldown 1m, delay 1-3 ticks, overhead

- Any time. Genuinely.
- Don't mention it. Please do mention it again though.
- That's what I'm here for.
- You're welcome. I'll be smug about it all day.
- Careful. I'll come to expect gratitude.
- I'll file that under rare events.
- Well now, manners. How lovely.

### `hovered`

> Being LOOKED at. The mouse resting on the follower for eight ticks is attention; a cursor crossing the screen is not, which is what the tick count is for. Costs nothing to know - the clickbox is already projected every client tick to draw the hover hint.

- **when:** being hovered 8+ ticks AND a 40% roll
- **does:** animation 863
- **pacing:** priority 30, cooldown 2m, overhead

- Yes? Something on my face?
- I can see you looking.
- Hello. You've been staring a while.
- Go on then, get a good look.
- Still here. Still fascinating, apparently.
- Take notes if you like. I do.
- I'd pose, but this is my only expression.

### `examined`

> Being looked up. The game gives its line about the follower; this is the follower's line about being looked up.

- **when:** being examined
- **does:** mood +3; animation 858
- **pacing:** priority 50, cooldown 1m, delay 2 ticks, overhead

- Yes, that's me. All of that is true.
- Reading up on me, are you?
- Always writing something. They're not wrong.
- Accurate, if a bit brief.
- Somebody wrote that about me. Fair play.
- That's the official version. The real one's longer.
- Examined! I feel positively catalogued.
- Go on, read the whole entry. I'll wait.
- That description undersells the notebook.
- Careful. I examine back.

### `want-fulfilled`

> The player actually went. This is the payoff for the whole want mechanism and the biggest single mood swing in the file: a thing with desires that can be SATISFIED reads as alive in a way no amount of commentary manages. {want} names the place it asked for.

- **when:** the want fulfilled
- **does:** mood +25; occasion (never held back); animation 2109; holds the floor 10s; holds still
- **pacing:** priority 78, cooldown 1m, delay 2-5 ticks, overhead

- {want}! You actually brought me. Thank you.
- You remembered. We're in {want}. I'm delighted.
- Look at that - {want}. You didn't have to do that.
- I asked for {want} and here we are. You're all right, you are.
- {want}, as requested. You take requests.
- We made it to {want}. The notes thank you.
- And here we are. {want}. Good as your word.

### `want-expired`

> Asked for, never done. A small drop, not a sulk - it has to be survivable or the follower becomes a chore.

- **when:** the want expired
- **does:** mood -8; animation 13997
- **pacing:** priority 34, cooldown 1m, overhead

- We never did get to {want}. Never mind.
- I'd given up on {want} anyway.
- So much for {want}, then.
- The page for {want} stays half written.
- {want} will keep. Everything keeps.
- Maybe {want} next time, then.

### `want-fulfilled-lumbridge`

> Arriving where it asked, with the REASON. The generic line thanks you for coming; this says why that place and not another, which is the difference between a companion that is pleased and one that has a preference. Above the generic want-fulfilled at 78, which stays as the fallback for any want without a line of its own. hushMs takes the floor for 10 seconds: the region change raises an area line at the same instant, and two lines about one arrival is one too many.

- **when:** the want fulfilled AND in region 12850
- **does:** mood +25; occasion (never held back); animation 2109; holds the floor 10s; holds still
- **pacing:** priority 80, cooldown 1m, delay 2-5 ticks, overhead

- Lumbridge. Everyone's first steps happen here. Mine were with you.
- Look at the church. Over two hundred years old, and still standing.
- The castle, the river, the old stone. Nothing happens here. That's the appeal.
- Thank you. Nobody's in a hurry in Lumbridge. Look at it.

### `want-fulfilled-grand-exchange`

> Arriving where it asked, with the REASON. The generic line thanks you for coming; this says why that place and not another, which is the difference between a companion that is pleased and one that has a preference. Above the generic want-fulfilled at 78, which stays as the fallback for any want without a line of its own. hushMs takes the floor for 10 seconds: the region change raises an area line at the same instant, and two lines about one arrival is one too many.

- **when:** the want fulfilled AND in region 12598
- **does:** mood +25; occasion (never held back); animation 2109; holds the floor 10s; holds still
- **pacing:** priority 80, cooldown 1m, delay 2-5 ticks, overhead

- The Exchange. Everyone here wants something, and you can see it on them.
- Listen to it. Hundreds of people haggling at once. I could watch all day.
- It's the noise I like. Nowhere else in the world sounds like this.
- Fortunes made and lost round here before lunch, and I get to watch it happen.

### `want-fulfilled-fishing-guild`

> Arriving where it asked, with the REASON. The generic line thanks you for coming; this says why that place and not another, which is the difference between a companion that is pleased and one that has a preference. Above the generic want-fulfilled at 78, which stays as the fallback for any want without a line of its own. hushMs takes the floor for 10 seconds: the region change raises an area line at the same instant, and two lines about one arrival is one too many.

- **when:** the want fulfilled AND in region 10293
- **does:** mood +25; occasion (never held back); animation 2109; holds the floor 10s; holds still
- **pacing:** priority 80, cooldown 1m, delay 2-5 ticks, overhead

- The Fishing Guild. It's the quiet. You don't get quiet like this anywhere.
- Listen. Nothing. Just the water. I needed this.
- I like it here because nothing is trying to kill us. Do you know how rare that is?
- The water does something to me. Sorry. I know it's only water.

### `burnt`

> A shake of the head at a run of burnt food. The game says "You accidentally burn the shrimps." and every third of those, while a cooking session is on, earns 856 (EMOTE_NO, proven by the answered-no rule). Silent: the head says it. The working condition comes first so the count only runs during the session.

- **when:** working AND a chat message (accidentally burn)
- **does:** animation 856
- **pacing:** priority 20, cooldown 30s

- *(no lines - animation or effect only)*

### `counting-batches`

> Counts your batches: every fifth fletching animation earns a nod, 855 (EMOTE_YES, in the mirrored emotes). 1248 is HUMAN_FLETCHING, the one the mime-loops rule already holds; the server restarts it per bow, and each restart is one animation event. Only while the fletching session is on.

- **when:** working AND your animation 1248
- **does:** animation 855
- **pacing:** priority 18, cooldown 3s

- *(no lines - animation or effect only)*

### `advice-taken`

> The other half of a warning. Until now the follower shouted and then never mentioned it again, whichever way it went, which is the difference between commentary and a conversation.

- **when:** advice taken
- **does:** mood +5
- **pacing:** priority 44, cooldown 5m, delay 2-5 ticks, overhead

- Thank you. That's all I wanted.
- There. Was that so hard?
- Good. I'll stop going on about it.
- You listened. I'm putting that in the book.
- You actually took my advice. Banner day, this.
- See? Painless.
- And that's why I bother saying things.
- Good. The book gets a tidy little tick.
- Sensible. I'll not let it go to your head.
- We work well when you do as I say. Coincidence, I'm sure.
- Ah. So you do hear me.
- Was that on my account, or were you going to anyway?
- Quietly pleased. That's as loud as I get.
- Right on cue. I'll pretend I timed that.
- I'll take that as listening. Don't correct me.
- Underlined. It's my page, I'll underline what I like.
- There's a version of this where I had to ask twice.
- My reputation as an oracle survives another day.
- Did that help? I'll write it down as though it did.
- Nothing left to nag about. I'll find something.

### `advice-ignored`

> And the funnier half.

- **when:** advice ignored
- **does:** mood -3
- **pacing:** priority 42, cooldown 2m, delay 2-5 ticks, overhead

- Right. I'll stop saying it, then.
- I did mention it. For the record.
- No? Fine. It's your skin.
- I'll just say it to myself next time.
- Ignoring me is a decision too. I've written it down.
- The margin note here just says 'told them'.
- As you like. The page remembers.
- One day the ledger of ignored advice gets read aloud.
- Duly noted. Duly ignored. The usual.

### `underfoot`

> The one ordinary nuisance of a companion that nothing acknowledged. Short lines: it is an interruption, and a paragraph would be worse than the standing there.

- **when:** underfoot
- **pacing:** priority 58, cooldown 45s, delay 1 ticks, overhead

- In your way, am I? Moving.
- Right, that's me in the way again.
- Excuse me. I'll just get out of your way.
- I was standing there. I'm aware.
- My fault. Shifting.
- And there I am, underfoot again.
- You walk, I dodge. That's the system.
- One of us has to give way. It's me. It's always me.

### `channel-joined`

> Somebody arrived in the friends chat or clan channel. Low duty cycle on purpose: a busy clan should be a murmur, not a roll call.

- **when:** channeljoined AND a 25% roll
- **pacing:** priority 16, cooldown 10m

- {name}'s turned up. I'll mark them present.
- There's {name}. I'll take the register.
- {name}'s in. I'll give them a line in the book.

### `channel-left`

> Somebody left the channel. The drier entry of the two.

- **when:** channelleft AND a 20% roll
- **pacing:** priority 15, cooldown 10m

- There goes {name}. The minutes will show it.
- {name}'s gone. Fewer witnesses.

### `npc-dictation`

> Any named NPC shouting overhead nearby - the town crier, a quest scene, a market stall. Heavy chance gate: busy scenes shout constantly.

- **when:** npcshout AND a 8% roll
- **pacing:** priority 12, cooldown 15m, delay 2 ticks

- I wrote down what {npc} said. Most of it.
- I heard you, {npc}. So did everybody.
- {npc} wants that on the record. It very nearly is.

### `examine-echo`

> The player examined something and the follower turned to look at it too. The head-turn is the usual whole reaction; this line is the garnish. Examining is player-INITIATED - they are asking for attention - so it answers readily, but not every single time.

- **when:** examinedthing AND a 70% roll
- **pacing:** priority 20, cooldown 30s, delay 2 ticks

- One {target}. Fair condition, I'd say.
- The {target}. I'll note it, if I must.
- There's a full page on the {target} somewhere. Not my best work.
- The {target} as well? We're being thorough today.
- Ah, the {target}. I have opinions.
- {target}. Write it down, we might be tested.
- You looked, so I looked. Teamwork.
- Is the {target} interesting? Be honest.
- A {target}, apparently. The description insists.
- I'd have guessed a {target} without reading it.
- Another {target} for the pile of things we've looked at.
- The {target}. Riveting stuff.
- That's the {target} noted, filed and forgotten by lunch.
- Looking closely at the {target}, are we?
- A {target}. I'd hoped for more, honestly.
- Somebody put real effort into describing the {target}.
- {target}. There. Now we both know.
- The {target}. Yes. Very much a {target}.
- Careful with that {target}. Oh, it's only a {target}. Carry on.
- One more {target} and it's going in the notebook.
- You really do read everything, don't you?
- Go on then. What did it say?
- I looked where you looked. I saw a {target}.
- You do like reading the small print.

### `minutes-taken`

> The follower just minuted one of the player's NPC dialogs - stepped aside, wrote through the meeting, put the scroll away as the box closed. The performance is the joke; the line is the rare receipt.

- **when:** minutestaken AND a 40% roll
- **pacing:** priority 30, cooldown 10m, delay 3 ticks

- I have the minutes, if it ever comes to a dispute.
- I've minuted every word of that. Some of the pauses, too.
- That's in the record now. They seemed nice.

## combat

*The fight from the sidelines, and its ends.*

### `combat-start-boss`

> Outranks combat-start so a boss gets its own line naming it.

- **when:** a fight starting AND a boss fight
- **does:** mood -4
- **pacing:** priority 60, cooldown 1m

- {npc}. I'll stand well back.
- {npc}. Take your time with this one.
- {npc}. Big one. You've got this.
- Careful with the {npc}.
- I'll keep my distance from the {npc}, thanks.
- {npc}? I'll want every detail of this.
- The {npc} is all yours. I insist.
- Big entry for the book, this one.

### `combat-start`

> Said as the follower steps clear of a fight.

- **when:** a fight starting
- **pacing:** priority 50, cooldown 2m

- Go on then. I'll watch from here.
- I'll stay out of your way.
- This one's yours. I'm backing up.
- Right. Room to swing.
- Giving you space.
- I'll be over here if it goes badly.
- Best of luck to the other one.
- I'll narrate quietly from here.
- Opening move's yours. It usually is.
- Deep breath. Now go and hit it.
- Started without me. Quite right too.
- I'll leave a space in the notes for how this goes.

### `combat-cheer-boss`

> Encouragement during a boss fight. Outranks the ordinary cheer.

- **when:** a boss fight AND a 12% roll
- **pacing:** priority 40, cooldown 15s

- Stay sharp.
- Mind the big one.
- You've beaten worse.
- Don't rush it.
- Breathe. You're fine.
- It's slowing down. I think.
- Hold your nerve.
- This is the tricky part.
- Patience wins this one.
- Eyes up. It's got more to give.
- Halfway. Roughly. Don't check.

### `combat-cheer`

> Encouragement during any fight, kept rare so it does not natter.

- **when:** in a fight AND a 8% roll
- **pacing:** priority 30, cooldown 20s

- Keep at it.
- That's it.
- You're winning this.
- Nice hit.
- Don't let up.
- Almost there.
- Good. Again.
- You've got the rhythm of it now.
- Steady.
- Watch its feet.
- I'd help, but you're doing fine.
- Still with you.
- That looked like it hurt. Them, I mean.
- Go on, finish it.
- You're making short work of it.
- Form's holding.
- Keep moving. Don't stand still for it.
- You've done harder.
- There's the opening.
- Won't be long now.

### `combat-end`

> The generic send-off, for ordinary fights only. A boss gets kill-boss-celebrate instead, which lands a few seconds later once the ward has wound down; both firing meant the boss was seen off with 'Well fought' and then congratulated properly eight seconds afterwards.

- **when:** a fight ending
- **pacing:** priority 50, cooldown 2m

- Well fought.
- That's that, then.
- Nicely done.
- I never doubted you.
- Coming back over.
- Right, where were we?
- Good. I can stop hiding.
- And breathe. All done.
- That's one more for the record.
- The quiet after a fight is my favourite part.
- Still in one piece. Both of us, even.
- Another chore ticked off, by the look of you.
- Fight's over. The paperwork begins.

### `kill-cheer`

> Sometimes cheers a kill. maximum 99 keeps this to ordinary monsters, leaving anything at the boss line to kill-boss-celebrate. holdStill plants the follower for the 2.4s cheer, since walking cancels an emote and it is usually chasing you to the next target.

- **when:** a kill AND a 30% roll
- **does:** mood +3; animation 862; holds still
- **pacing:** priority 55, cooldown 40s, delay 1-3 ticks, overhead

- Nice one!
- Got it!
- That's the way!
- Well struck!
- Down it goes.
- You made that look easy.
- Good hit!
- That {npc} never stood a chance.
- Clean work.
- Another one down.
- Yes! Straight through it.
- I saw that. That was good.
- Textbook. If there were a textbook.
- And down. Barely a scuff on you.
- The {npc} has filed its complaint.
- Nice and quick. I approve.

### `mood-recovering`

> A note after climbing out of a bad stretch. It used to trigger on a BOSS kill, which is the one kill that already has kill-boss-celebrate sitting on it at priority 70 - so whenever this matched, that matched too and outranked it, and this could only ever have fired in the celebration's cooldown gap. Two boss kills inside thirty seconds is not a thing, so it never fired at all. Ordinary kills now, where the big moment is not already spoken for, and above kill-cheer: the day turning is worth more than a generic cheer.

- **when:** a kill AND mood is down
- **pacing:** priority 60, cooldown 3m, delay 2-5 ticks, overhead

- There. That's more like it.
- See? The day turns.
- That's the one we needed.
- Better. Much better.
- The day's looking up. Cautiously.
- There it is. Knew it was in there somewhere.
- Right, the slump's over. Onward.

### `kill-tally`

> Notices the count. 'every': 25 fires on the twenty-fifth of THAT npc and every twenty-fifth after, with {count} carrying the number and {npc} the name. Nothing says paying attention like a tally.

- **when:** a kill
- **does:** mood +2
- **pacing:** priority 58, cooldown 20s, delay 2-5 ticks, overhead

- That's {count} of them. Not that I'm counting. I am counting.
- {count}. I've been keeping track, since nobody else was.
- {count} now. The {npc} population is having a bad day.
- Number {count}. You could stop. You won't, but you could.
- {count}. At some point this stopped being a fight and became a job.
- {count}. The tally marks are becoming a drawing.
- {count}. The {npc} column's run off the page.
- Entry {count}. Same as entry one, but later.

### `kill-tally-hundred`

> The bigger milestone, at every hundredth of one npc. Higher priority than kill-tally so the round number wins when both come up together.

- **when:** a kill
- **does:** mood +5; prop item 31830, pose 13302
- **pacing:** priority 62, cooldown 20s, delay 2-5 ticks, overhead

- {count}. I do hope this is all for something.
- {count} of them. You realise that's a village?
- {count}. I've watched every single one. Ask me anything.
- That's {count}, and I'd like it noted that I never once complained.
- {count}. Some scribes record wars smaller than this.
- {count}. I had to start a second page.
- {count}, and my quill would like a word.

### `record-hit`

> A new biggest hit. {value} is the new mark and {previous} the one it beat, which is what makes it land - a record without the old number is just a number. The first hit after an install seeds the record silently and says nothing, so this cannot fire on day one.

- **when:** a personal best (hit)
- **does:** mood +12; marks this place: 'you hit harder than you ever had'; animation 862
- **pacing:** priority 68, cooldown 20s, overhead

- {value}! Hardest I've ever seen you hit, and I was there for the {previous}.
- {value} damage. You'd never got past {previous} before.
- Did you see that? {value}. New best.
- {value}. The old record was {previous}. Was.
- A {value}! Underline it. Twice.
- That's {value}, a new best. The book takes back what it said.

### `something-big-nearby`

> Nerves. npcNearby with a combat level and no names at all, so this covers every large thing in the game and everything added later without a list to maintain. Suppressed during a fight - once it has started, saying something big is nearby is not news.

- **when:** a 25% roll AND NOT (in a fight) AND any within 8 tiles, in sight
- **does:** mood -5; animation 14001
- **pacing:** priority 52, cooldown 4m, overhead

- That thing is very large and very close.
- You have seen what's standing over there? Just checking.
- We're stood very close to something that could end this quickly.
- Are we meant to be this close to that?
- Official measurement: enormous.
- I'm noting its size from a distance. There is no safe distance.

### `flinch-big-hit`

> A flinch, and nothing else. Animation 424 is HUMAN_UNARMEDBLOCK, 1.26s, the unarmed defensive recoil. Silent on purpose: a body that reacts before the mouth does is most of what makes something look like it is watching, and a line here would be one more thing said during a fight that is already noisy.

- **when:** taking 20+ damage
- **does:** animation 424
- **pacing:** priority 48, cooldown 8s

- *(no lines - animation or effect only)*

## health

*Hitpoints, prayer, poison, the warnings that must land.*

### `low-hp`

> Speaks once when health crosses under 40 percent.

- **when:** HP under 40%
- **does:** occasion (never held back); advises: food
- **pacing:** priority 100, cooldown 15s, both

- Eat something! {hp} out of {maxHp} is not a plan.
- You're on {hp}. I'd feel better if you weren't.
- Getting low, {player}. Food exists for a reason.
- That's {hpPercent} percent. Top up when you get a breath.
- I've seen you fight better on a full belly. Just saying.
- Health's dipping. Nothing a bite won't fix.
- Steady on, {hp} left. Mind the next hit.
- A shark about now wouldn't hurt. Well. It would help.
- You handle the fight, I'll do the worrying. You're on {hp} of {maxHp}.
- Low health, high spirits. Let's fix the first one.

### `critical-hp`

> Speaks once when health crosses under 20 percent. The urgent one.

- **when:** HP under 20%
- **does:** mood -15; occasion (never held back); advises: leaving; animation 2105
- **pacing:** priority 120, cooldown 8s, both

- TELEPORT. NOW.
- {hp} HP! Get out!
- This is the dangerous kind of low. Move!
- Eat, teleport or run. Pick one, quickly!
- {hp} left. I'm officially worried.
- Whatever you're doing, it can wait. LIVE first.
- That health bar is a rumour at this point. Go!
- No shame in leaving. Plenty of shame in a gravestone.
- You've survived worse. Prove it. Now.
- Panic usefully! Food, then feet!

### `low-prayer`

> Speaks once when prayer crosses under 20 percent with prayers active.

- **when:** prayer under 20%
- **does:** occasion (never held back)
- **pacing:** priority 95, cooldown 12s, both

- Prayer's nearly out. {prayer} points left.
- Sip a restore, you're on {prayer} prayer.
- The gods are barely hearing you. {prayer} left.
- Your prayers are running on fumes.
- Restore now, thank me later.
- Down to {prayer} prayer points. Your overheads won't last.
- The faith is strong. The points, less so.
- Keep those prayers fed or let them rest, friend.
- An altar or a potion, I don't mind which. Soon, though.
- Running dry impresses no god. {prayer} left.

### `status-poisoned`

> Speaks once each time you become poisoned (not venomed).

- **when:** poisoned
- **does:** occasion (never held back)
- **pacing:** priority 105, cooldown 30s, both

- You're poisoned. Antidote, when you can.
- That green tint is not a good look on you.
- Poison in the veins. Don't just walk it off.
- Something bit deep. Cure it before it adds up.
- You're dripping green numbers. I hate the green numbers.
- Poisoned again? Sip the antipoison, hero.
- It'll keep biting until you deal with it.
- Poison's patient. You don't need to be.
- One little vial of antipoison and this all goes away.
- Remind me to pack more antipoison next time.

### `status-venomed`

> Speaks once each time you become venomed. Venom outranks poison.

- **when:** venomed
- **does:** occasion (never held back)
- **pacing:** priority 115, cooldown 30s, both

- That's VENOM, not poison. It gets worse. Fix it!
- Antipoison won't touch this. It has to be anti-venom!
- The numbers are climbing. Venom does that. Cure it!
- Whatever bit you meant it. Anti-venom, quickly.
- Green and getting greener. This one won't stop on its own.
- Venom bites harder the longer you wait.
- This is the serious green. Please respect the serious green.
- Cure that before it hits harder than the monster does.
- I can smell it from here. Anti-venom, now!
- Your blood is losing an argument. Send in the potion.

### `status-skulled`

> Speaks once when a skull appears over your head.

- **when:** skulled
- **does:** occasion (never held back)
- **pacing:** priority 85, cooldown 1m, both

- You're skulled. Everything you carry is on the table now.
- That skull over your head is not a fashion statement.
- Skulled! Watch every stranger like they owe you money.
- Until that fades, it's a target over your head, not a skull.
- Whatever you did, half the Wilderness felt it. Stay sharp.
- Skull's up. Every fight's a gamble now.
- I'd keep the valuables at home until that clears.
- You look terrifying. Also very worth killing. Careful.
- The skull fades with time. Patience and paranoia, in that order.
- Bold move. Now let's live long enough to regret it properly.

### `status-low-energy`

> Speaks when run energy crosses under 15 percent. Long cooldown so it never nags.

- **when:** run energy under 15%
- **pacing:** priority 15, cooldown 4m, overhead

- Out of breath already? We've got places to be.
- Have a rest. I'll pretend I'm not judging.
- Your legs called. They'd like a word.
- Energy's spent. Walk it off, literally.
- A stamina potion would change your life, you know.
- We can walk. Walking is dignified. Sort of.
- All that gear and no wind. Happens to the best of us.
- Agility training pays off eventually. Allegedly.
- Catch your breath. The scenery's nice enough.
- I could carry you. I won't. But I could.

### `big-hit-taken`

- **when:** taking 30+ damage
- **does:** animation 424
- **pacing:** priority 60, cooldown 12s

- Ouch. {damage}.
- That was a {damage}. I felt that one from here.

## memory

*Deaths, incidents, comforts, the things it keeps bringing up.*

### `memory-close-call`

> Filed when you come out the far side of nearly dying. The follower does not need to say anything at the time - critical-hp already does - it just needs to remember, so it can bring it up later. The particulars (who, how many hitpoints, which item, where, when) are filled in when it is filed, so a recall can jog the memory rather than allude to it.

- **when:** HP under 8% AND in a fight
- **does:** files incident `close-call` as 'how close that was with the {foe}'
- **pacing:** priority 30, cooldown 5m, overhead

- I'm not going to forget that one.
- That'll stay with me, that will.
- Too close. The handwriting shows it.
- That gets an underline.
- My heart was in my mouth for that one.

### `memory-death`

> The obvious one. Filed on every death, so the count is what makes the recall land: once is bad luck, four times is a habit. The particulars (who, how many hitpoints, which item, where, when) are filled in when it is filed, so a recall can jog the memory rather than allude to it.

- **when:** on your death
- **does:** files incident `the-death` as 'the way that went with the {foe}'; prop item 31830, pose 13302
- **pacing:** priority 30, cooldown 5s, delay 6-12 ticks, overhead

- That's going in the book.
- I saw all of it, for what it's worth.
- The record will be kind about it.
- I've written it down. Not happily.
- Some entries I would rather not have.
- Filed under things we don't mention.
- The ink went on heavier than usual for that one.
- I wrote it smaller, so it looks further away.
- One more for the chapter I keep meaning to close.

### `memory-big-drop`

> Filed on a drop big enough to still be talking about an hour later. Above the loot-celebrate threshold so the celebration wins the event and this one only files - two lines about one drop is one too many, and the recall is the part that matters here. The particulars (who, how many hitpoints, which item, where, when) are filled in when it is filed, so a recall can jog the memory rather than allude to it.

- **when:** loot worth 1000000+
- **does:** files incident `big-drop` as 'the {item} that dropped'; prop item 31830, pose 13302
- **pacing:** priority 20, cooldown 10m, overhead

- I'm still not over that.
- That drop gets its own footnote.
- I re-read that entry sometimes. For morale.
- Best line in the ledger so far.

### `memory-boss`

> Filed when something with a combat level in three figures stops moving. Low priority so the celebration rules win the kill. The particulars (who, how many hitpoints, which item, where, when) are filled in when it is filed, so a recall can jog the memory rather than allude to it.

- **when:** a kill
- **does:** files incident `the-boss` as 'the {npc} going down'; prop item 31830, pose 13302
- **pacing:** priority 20, cooldown 10m, overhead

- I want that written down somewhere.
- That one goes in with a steadier hand.
- Chapters end on moments like that.
- Tell people. If you don't, I will.

### `recall-idle`

> The whole point of the incident. Brought up unprompted, in a quiet moment, about the specific thing that happened rather than about the number of times something like it has. Deliberately rare.

- **when:** standing still 120+ ticks AND standing still under 600 ticks AND an incident remembered AND a 4% roll
- **pacing:** priority 24, cooldown 15m, overhead

- I keep thinking about {memory}.
- I was rereading the entry on {memory}.
- Do you ever think about {memory}? I do.
- It's quiet, so naturally my mind goes back to {memory}.

### `recall-death-third`

> The third death exactly, so the count is true when it is said. The death being spoken of is filed after the line is chosen, so the count read here is the deaths before it: two. Outranks recall-death-again, which takes over from the fourth.

- **when:** on your death AND an incident remembered
- **does:** occasion (never held back)
- **pacing:** priority 93, cooldown 2m, delay 1-2 ticks, overhead

- That's three. I've started keeping count, and I wish I hadn't.

### `recall-death-again`

> Named rather than generic, because the count is the joke and only this incident has a count worth making one of. The count read is the deaths before this one, so a minimum of 2 is the third death on.

- **when:** on your death AND an incident remembered
- **does:** occasion (never held back)
- **pacing:** priority 93, cooldown 2m, delay 1-2 ticks, overhead

- I'm starting to see a pattern, and I don't like it.
- We've done this before. Several times. Recently.
- Same ending, new page.
- The entries are starting to rhyme.
- I'd say lightning doesn't strike twice, but here we are.
- At this point the margin notes are just sighs.
- I could trace this one straight off the last.

### `recall-close-call-warning`

> The line that could not exist without incidents: advice with a reason attached, and the reason is something you were both there for. Fires on going back into a fight, not on idling.

- **when:** a fight starting AND an incident remembered AND a 25% roll
- **pacing:** priority 52, cooldown 10m, overhead

- Careful. I've not forgotten {memory}.
- Before you start. Remember {memory}?
- Go on then. But I'm watching, after {memory}.
- Mind how you go. The ink's barely dry on {memory}.

### `recall-here`

> Back in the region the incident happened in. The rule is state, so it fires once on the rising edge, which is the moment of arriving; the cooldown keeps a day at the same spot to one remark.

- **when:** memoryhere AND a 60% roll
- **pacing:** priority 40, cooldown 30m, delay 3-6 ticks, overhead

- Stand still a moment. The notes on {memory} start here.
- Here again, and I've not forgotten {memory}.
- I know this spot. This is where I wrote down {memory}.
- It was right about here, {memoryWhen}. The page on {memory} is still creased.

### `recall-foe`

> One of the things the incident was with, back in sight. {memoryWho} is the NPC's name off the record; memoryFoeNearby guarantees it is there to print.

- **when:** memoryfoenearby AND a 50% roll
- **pacing:** priority 45, cooldown 20m, delay 2-4 ticks, overhead

- That's a {memoryWho}. You'll remember {memory}.
- A {memoryWho}. I remember {memory}.
- Mind the {memoryWho}. I've got a page on those, and it's not a kind one.
- There's the {memoryWho}. I was there for {memory}.

### `favour-remembered`

> Three outings taken. The follower is not thanking you for the last one; it is thanking you for the pattern, which is the difference between politeness and knowing somebody.

- **when:** want:kept count at 3+ AND standing still 100+ ticks AND a 8% roll
- **does:** mood +6
- **pacing:** priority 26, cooldown 1h, overhead

- You've humoured my outings. I've filed that somewhere safe.
- I ask for an outing and off we go. Not everyone would.
- No complaints. That's not something I say lightly.
- For the record, you keep your promises.
- Everywhere I ask to go, we go. Don't think I haven't noticed.
- Your side of the favours ledger is looking healthy.

### `grudge-noted`

> And the other ledger. Not sulking - noting. A follower that forgot the times you said no would not be much of a companion either.

- **when:** want:missed count at 3+ AND want:kept count at 0+ AND standing still 100+ ticks AND a 8% roll
- **does:** mood -4
- **pacing:** priority 26, cooldown 1h, overhead

- That's a few times I've asked to go somewhere. I'm not sulking.
- I ask less than I used to. You may have noticed.
- Next time I ask, I'll set my hopes low first.
- The requests column has a lot of blank replies.
- I mention places. We stay put. It's a pattern.
- Fine. The wanting was probably good for me.

### `comforted`

> The player picked 'You all right?' on a lowish day. Asking is the kindness; this is the lift and one soft overhead coda after the box closes.

- **when:** the player answering 'comforted'
- **does:** mood +6
- **pacing:** priority 50, cooldown 5m, delay 2 ticks, overhead

- I'm lighter for being asked, oddly enough.
- You checked on me. That does help.

### `advice-never-listens`

> The ledger, the same shape as the outings one: ten pieces of advice into the wind is a fact about the pair of you rather than about any one of them.

- **when:** advice:ignored count at 10+ AND standing still 100+ ticks AND a 8% roll
- **pacing:** priority 28, cooldown 45m, overhead

- At least ten times I've told you something, and you did as you liked.
- Stopped counting how often you ignore me. That's a lie. I know exactly.
- You never listen. I've decided that's part of your charm.
- My advice does its best work as background noise.
- I run an advice column for one reader, and you skip it.
- One day you'll take a suggestion and I'll faint.

### `advice-good-listener`

> And the warm end of it.

- **when:** advice:taken count at 10+ AND standing still 100+ ticks AND a 8% roll
- **does:** mood +6
- **pacing:** priority 28, cooldown 45m, overhead

- You actually listen to me. Do you know how rare that is?
- I say something, you do it. We're a good team, whatever anyone says.
- I've never had to tell you twice. Well. Rarely.
- Advice given, advice taken. The page is tidy.
- You make being right feel almost useful.
- Between us, we're nearly sensible.

### `known-a-week`

> Pinned with a maximum so the week line stops being true at a month and shouting over the month one.

- **when:** known 7-29 days AND standing still 100+ ticks AND a 6% roll
- **pacing:** priority 27, cooldown 1h, overhead

- A week and counting, you and me. Settling in nicely.
- {days} days I've been following you about. Nothing to report.
- Past the first week. The notes are getting personal.
- {days} days. Long enough to learn your walk.

### `known-a-month`

- **when:** known 30-99 days AND standing still 100+ ticks AND a 6% roll
- **pacing:** priority 27, cooldown 1h, overhead

- A month and more of this. I'd not have guessed it would stick.
- {days} days. Not that I keep a calendar. I keep a calendar.
- A month, give or take. Mostly give.
- {days} days in. The early pages already read like history.

### `known-a-hundred-days`

- **when:** known 100-364 days AND standing still 100+ ticks AND a 6% roll
- **pacing:** priority 27, cooldown 1h, overhead

- {days} days I've known you. That's a proper stretch, that is.
- Over a hundred days. I've seen you at your best and the other thing.
- {days} days. The notebook needed a second volume.
- A hundred days and more. You've not got rid of me yet.

### `known-a-year`

> The one that could not exist without a date. A session count can say a hundred logins; only a calendar can say a year, and they are different claims about a friendship.

- **when:** known 365+ days AND standing still 100+ ticks AND a 10% roll
- **pacing:** priority 29, cooldown 2h, overhead

- Over a year, this. {days} days. I looked it up.
- A year of walking behind you. I'd do it again.
- {days} days. I'm not going anywhere, in case that was in doubt.
- {days} days, and I could still tell you about day one.
- A year and more. The spine's cracked on this volume.

### `anniversary`

> The same day of the year as the first one, the first year only: every line says a year. Priority high enough to beat the everyday chatter, because it happens once.

- **when:** the anniversary AND known 1-400 days AND standing still 20+ ticks
- **does:** occasion (never held back); animation 863; holds the floor 8s; holds still
- **pacing:** priority 68, cooldown 2h, overhead

- It's today, you know. A year since we first met. I remembered.
- A year ago today. Same face, better hat.
- Happy anniversary. I'll not make a fuss. That was the fuss.
- I wrote the date down the day we met. Today it's a year.
- Today's the day, a year on. No fuss, just the one line in the book.

### `nickname-earned`

> Drawn from whichever tally is highest, so it moves as your play moves, and said only once the player has AGREED to it through the ask-nickname question. The names live at the top of this file, not in the code.

- **when:** nickname earned AND standing still 80+ ticks AND a 5% roll
- **pacing:** priority 26, cooldown 30m, overhead

- All right, {nickname}.
- I've got you filed under {nickname}. It fits.
- If anyone reads these notes, they'll know you as {nickname}.
- It's official. The index says {nickname}.
- History gets a say, and it says {nickname}.
- {nickname}. Cross-referenced and everything.

### `ask-nickname`

> The earned name, proposed rather than used. The overhead line says the candidate; the tree in dialogs.json asks whether the player minds, and only a yes makes it the nickname the other lines say. A no is remembered and that name is never offered again. Every asks rule needs the none/asking guard.

- **when:** nicknameearned AND NOT (repeating one action 100+ ticks) AND standing still 40+ ticks AND standing still under 400 ticks AND NOT (wanting somewhere OR a question open) AND a 10% roll
- **does:** opens question `nickname-offer`
- **pacing:** priority 27, cooldown 30m, overhead

- I've been calling you {candidate} in the notes. Tell me if that's all right.
- In the index you're down as {candidate}. Talk to me if you object.
- I'm thinking of filing you under {candidate}. Come and tell me if you mind.

### `nickname-accepted`

> A yes to the nickname question. The name is settled before this fires, so {nickname} is the one just agreed.

- **when:** the player answering 'yes' AND nickname earned
- **does:** mood +6
- **pacing:** priority 80, cooldown 10s, delay 2-4 ticks, overhead

- {nickname} it is. Underlined.
- Good. {nickname} goes on the spine.
- Then that's settled. {nickname}.

### `nickname-refused`

> A no to the nickname question. Struck through and never offered again; a different name earned later is asked about afresh.

- **when:** the player answering 'no'
- **does:** mood -2
- **pacing:** priority 80, cooldown 10s, delay 2-4 ticks, overhead

- Fair enough. Struck through.
- Then it stays off the record. I'll think of another.
- Right. Back to your proper name in the file, then.

### `nickname-later`

> Ask me later. The candidate stays a candidate and the question comes round again after its cooldown.

- **when:** the player answering 'later'
- **pacing:** priority 80, cooldown 10s, delay 2-4 ticks, overhead

- In pencil, then.
- I'll ask again. Patience is most of the job.
- Later it is. The index can wait.

### `grateful-idle`

> The half hour after the player did what the follower asked - went where it wanted, brought what it wished for. The body pays the favour back on its own (closer attention, quicker gestures); this is the rare line that says so.

- **when:** grateful AND standing still 100+ ticks AND a 5% roll
- **pacing:** priority 24, cooldown 20m, overhead

- Still in your debt from earlier. I'm keeping a closer eye today.
- I haven't forgotten the favour. You'll get my best work today.
- You did the thing I asked. I've been standing a little straighter since.

### `outgrew-the-gear`

> Ten times better dressed than the day it met you. Needs a first-meeting figure, so a follower that has only ever known you rich stays quiet rather than inventing a humble beginning.

- **when:** gear outgrown (10x) AND standing still 60+ ticks AND a 8% roll
- **does:** mood +8
- **pacing:** priority 30, cooldown 2h, overhead

- Look at you now. You were in rags when we met.
- I remember what you turned up in. This is better.
- You've come up in the world. I was there for the bottom of it.
- The kit's improved beyond recognition. The walk's the same.
- Page one has you in your old kit. I look at it whenever you get too grand.

### `notes-edited`

> phrases.json changed on disk and hot-reloaded. In-world, somebody wrote in the scribe's own book. Long cooldown so an editing session earns one remark.

- **when:** notesedited AND a 75% roll
- **pacing:** priority 40, cooldown 30m, delay 3 ticks

- This isn't my handwriting.
- Someone's been in my notes. The margins are different.
- The annotations are an improvement. Worrying.
- My notebook smells of fresh ink I didn't buy.

## area

*Places: named lines, tastes, earned verdicts, the defences.*

### `enter-wilderness`

> Fires on crossing into the Wilderness, via the wilderness varbit (5963, INSIDE_WILDERNESS). Read as state, not as a change: a varbitChanged rule anywhere in the set switches the varbit flood back on. Dialogue check, 14 Oct: it took in the lines of wilderness-crossing, which rose on the same tick, lost to it every time and so was never heard.

- **when:** varbit 5963 = 1
- **does:** occasion (never held back)
- **pacing:** priority 110, cooldown 30s, delay 2 ticks, both

- Wilderness. Don't get skulled.
- We're in the Wildy. Stay sharp.
- Everything north of that ditch wants your items. Everything.
- If someone waves at you out here, they're not being friendly.
- The revenants used to be worse, if you can believe it.
- Don't bring anything you'd cry about losing. Too late now, I suppose.
- You've crossed. You know what that means.
- Right. Wilderness. Everyone here can kill you now.
- Oh, we're doing this. Fine. I'm staying close.
- That's the ditch behind us. No takebacks.
- Speak softly out here, and keep a teleport handy.
- North it is. I'll keep the notes brief and my eyes open.

### `area-lumbridge`

> Lumbridge town and castle.

- **when:** in region 12850
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Lumbridge. Goblins in one field, cows in the next. Very rural.
- Named after the bridge over the River Lum, you know. The locals are very proud of it.
- The Duke's still up in that castle, worrying about his shield.
- Smell that? Castle kitchen. The cook's probably out of eggs again.
- Ah, Lumbridge. I hear the gravestones are lovely this time of year.
- The swamp's just south. Try not to fall in any holes.

### `area-grand-exchange`

> The Grand Exchange plaza.

- **when:** in region 12598
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Grand Exchange. Somebody's getting rich in there right now.
- Listen to them shout numbers at each other. Magnificent.
- Careful with your coin here. The clerks never sleep.
- This was a plain old field once, they say. Now look at it.
- If you're buying, someone's selling. Usually at twice the price.
- Half of Gielinor's wealth walks through here. Try to keep yours.

### `area-varrock`

> Varrock: west bank, centre and palace.

- **when:** in region 12597, 12853, 12854
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Varrock. The old-timers still call it Avarrocka.
- Biggest city in Misthalin, and it has the pickpockets to prove it.
- The Blue Moon Inn pours a decent ale, if you're stopping.
- Mind the stray dog by the square. He's harmless. Mostly.
- King Roald rules from that palace. Well. His advisors do.

### `area-falador`

> Falador, both halves of the white city.

- **when:** in region 11828, 12084
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Falador, the white city. Do wipe your boots.
- The White Knights run things here. Very shiny. Very serious.
- There's a whole dwarven mine under our feet, you know.
- The Party Room's here. People throw fortunes into that chest.
- Wyson tends the park. Bring him mole bits, he loves that.

### `area-edgeville`

> Edgeville.

- **when:** in region 12342
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Edgeville. Last stop before the Wilderness.
- Cosy little place, considering what's just north of it.
- The monks at the monastery brew something special, I hear.
- These ruins were grand once. Then the gods had their war.
- People bank here before doing something brave. Or foolish. Usually both.

### `area-ferox-enclave`

> Ferox Enclave, the Wilderness safe haven. Higher priority than the wilderness line.

- **when:** in region 12344
- **pacing:** priority 45, cooldown 3m, delay 2 ticks, overhead

- Ferox Enclave. Safe ground, strange as that sounds out here.
- The pool here fixes everything. Wish I could bottle it.
- Somebody carved a bit of peace out of the Wilderness. That took some doing.
- Deep breath. It's back to danger the moment we step out.

### `area-draynor`

> Draynor Village.

- **when:** in region 12338
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Draynor Village. Keep a hand on your coin pouch.
- The Wise Old Man robbed the bank here in broad daylight. I almost admire it.
- That manor up the road? Folk go in the front door and come out the back, running.

### `area-al-kharid`

> Al Kharid.

- **when:** in region 13105
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Al Kharid. Mind the heat, mind the scimitar prices.
- Ten coins at the gate, or a whole quest. I've never forgiven that toll.
- Get a kebab while we're here. You can't go wrong for one coin. Probably.

### `area-port-sarim`

> Port Sarim.

- **when:** in region 12082
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Port Sarim. Smells of fish, tar and bad decisions.
- Boats to everywhere, if you've got the stomach for it.
- Careless folk end up in the jail here. Wave at Wormbrain.

### `area-rimmington`

> Rimmington.

- **when:** in region 11826
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Rimmington. Quiet. Suspiciously quiet.
- Folk build lovely houses around here, I'm told.

### `area-taverley`

> Taverley.

- **when:** in region 11573
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Taverley. Druids, herbs and very good air.
- All stone circles and potions here. Peaceful, mostly.
- The dungeon under this village goes DOWN. Black dragons down.

### `area-burthorpe`

> Burthorpe.

- **when:** in region 11575
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Burthorpe. The Imperial Guard holds the troll line here.
- Trolls in the mountains, heroes in the pub. Balance.
- The Heroes' Guild is close. One day, eh?

### `area-catherby`

> Catherby.

- **when:** in region 11061
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Catherby. Best fishing beach in Kandarin.
- Nets, harpoons, lobster pots, this town runs on fish.
- Lovely little farm patch up the hill. Smells better than the fish.

### `area-seers-camelot`

> Seers' Village and Camelot castle.

- **when:** in region 10806
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Seers' Village. They probably knew we were coming.
- Camelot. King Arthur and his knights, actual legends, right there.
- The seers see all futures. Awkward at parties, I imagine.
- Flax everywhere. Some folk have picked it for years and won't say why.

### `area-east-ardougne`

> East Ardougne and its market.

- **when:** in region 10291, 10547
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Ardougne. Biggest market in Kandarin, fastest fingers too.
- Watch the stalls. Watch the guards watching the stalls.
- King Lathas rules here. Don't ask me what I think of him.

### `area-west-ardougne`

> West Ardougne, behind the wall.

- **when:** in region 9779, 10035
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- West Ardougne. The wall tells you everything, really.
- Grim over here. The people deserve better.
- They say it's the plague. I've got my doubts.

### `area-yanille`

> Yanille.

- **when:** in region 10288
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Yanille. Built to keep the ogres out. Sturdy work.
- Watchtower to the west, wizards in the guild. Magic town, this.
- Ogres have battered these walls for years. The walls are winning.

### `area-castle-wars`

> Castle Wars lobby area.

- **when:** in region 9776
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Castle Wars. Old grudges, fresh barricades.
- Saradomin and Zamorak settle it here, over and over.
- Grab a flag, lose your dignity. Tradition.

### `area-gnome-stronghold`

> Tree Gnome Stronghold.

- **when:** in region 9525, 9781
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Tree Gnome Stronghold. Mind where you step. Gnomes everywhere.
- That's the Grand Tree. It's a tree AND a government.
- Gnome cuisine is... an experience. Try the crunchies.

### `area-barbarian-village`

> Barbarian Village.

- **when:** in region 12341
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Gunnarsgrunn. Though everyone just says Barbarian Village.
- They fish with their bare hands here. Show-offs.
- That hole in the middle goes down further than anyone admits.

### `area-rellekka`

> Rellekka.

- **when:** in region 10553
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Rellekka. Fremennik country, so best not mention magic.
- Everything here is earned. Names included.
- The longhall ale flows all night. So does the singing, unfortunately.

### `area-canifis`

> Canifis.

- **when:** in region 13878
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Canifis. Lovely people. Don't stare at their teeth.
- Everyone here is a werewolf. Everyone. Walk casual.
- The general store meat is best left unexamined.

### `area-barrows`

> The Barrows mounds.

- **when:** in region 14131
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Barrows. Six brothers, six mounds, no rest.
- The brothers were heroes once. Their reward was... this.
- Dig if you dare. They do bite back.

### `area-port-phasmatys`

> Port Phasmatys.

- **when:** in region 14646
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Port Phasmatys. A town of ghosts, and it still charges tolls.
- The ghosts here can't leave. Necrovarus saw to that.
- Bring ectoplasm. It's the only coin that counts here.

### `area-lunar-isle`

> Lunar Isle.

- **when:** in region 8253
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Lunar Isle. The Moon Clan dream deeper than most folk live.
- Half of what goes on here happens in somebody's sleep.
- Mind the suqah. All that grace, none of the manners.

### `area-miscellania`

> Miscellania.

- **when:** in region 10044
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Miscellania. Somebody important runs this place, I hear.
- The subjects will work, if someone keeps the coffers full.

### `area-sophanem`

> Sophanem.

- **when:** in region 13099
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Sophanem, city of the dead. They mean that literally.
- Plagues, locusts, tombs. Charming place for a stroll.

### `area-mor-ul-rek`

> Mor Ul Rek, the TzHaar city.

- **when:** in region 9808
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Mor Ul Rek. The TzHaar are made of living rock. Mind the handshakes.
- Everything here is obsidian. Even the shopkeepers.
- Jad's in there somewhere. We don't have to say hello.

### `area-brimhaven`

> Brimhaven.

- **when:** in region 11057
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Brimhaven. A pirate town, and folk still pay good money to live here.
- The agility arena's here, if you fancy dodging darts and spikes for tickets.

### `area-musa-point`

> Musa Point, Karamja.

- **when:** in region 11569
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Musa Point. Bananas as far as the eye can see.
- Luthas pays coin for banana crates. He never checks what else goes in them.
- Karamja rum doesn't travel well. Famously.

### `area-shilo-village`

> Shilo Village.

- **when:** in region 11310
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Shilo Village. They fought the dead for this town.
- The gem mine here made a lot of folk rich. The cart ride's extra.

### `area-crandor`

> Crandor.

- **when:** in region 11314
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Crandor. Elvarg burned this whole island to cinders.
- A city stood here once. One dragon later...

### `area-entrana`

> Entrana.

- **when:** in region 11316
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Entrana. Holy ground. The monks made us leave ALL the weapons behind.
- The law altar's here. Fitting, with all these rules.
- Deep breath. No fighting. It's actually rather nice.

### `area-ape-atoll`

> Ape Atoll.

- **when:** in region 11051
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Ape Atoll. The monkeys are armed and extremely accurate.
- Walk like you belong or we're both getting arrested by gorillas.

### `area-zanaris`

> Zanaris.

- **when:** in region 9797
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Zanaris, the lost city itself. It took some finding.
- This place is the moon, you know. The actual moon.
- Fairy rings can take you anywhere from here. Well. Almost.

### `area-keldagrim`

> Keldagrim.

- **when:** in region 11679
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Keldagrim. The dwarves built a whole city and kept it quiet.
- The Consortium runs everything here. Eight companies, one grudge each.

### `area-fossil-island`

> Fossil Island, museum camp side.

- **when:** in region 14907
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Fossil Island. The museum folk barely scratched the surface.
- Ancient wyverns sleep in the caves here. SLEEP. Let's keep it that way.

### `area-god-wars`

> The God Wars Dungeon.

- **when:** in region 11602
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The God Wars Dungeon. The war never ended down here.
- Four armies, frozen mid-battle for thousands of years. And us, visiting.
- Wear their colours or they WILL take it personally.

### `area-taverley-dungeon`

> Taverley Dungeon.

- **when:** in region 11673
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Taverley Dungeon. It gets worse the deeper you go. Much worse.
- Black dragons at the bottom, and something meaner below.

### `area-catacombs`

> Catacombs of Kourend.

- **when:** in region 6557
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Catacombs of Kourend. The dark altar hums through these walls.
- The dead down here don't rest. Blame the altar.

### `area-warriors-guild`

> Warriors' Guild.

- **when:** in region 11319
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Warriors' Guild. Even the armour fights here.
- Harrallak collects the finest warriors. And us, apparently.

### `area-fishing-guild`

> Fishing Guild.

- **when:** in region 10293
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Fishing Guild. Sixty-eight and not a level lower, the door says.
- The sharks here practically volunteer.

### `area-wintertodt`

> Wintertodt camp.

- **when:** in region 6462
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Wintertodt. The cold out here is ALIVE. Layer up.
- The pyromancers hold it back with bonfires and stubbornness.
- Feed the flames. I'll supervise from a warm distance.

### `area-motherlode-mine`

> Motherlode Mine.

- **when:** in region 14936
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Motherlode Mine. Percy's pay-dirt racket, and we're all in on it.
- Listen to that water wheel. Honest work, this.

### `area-mining-guild`

> Mining Guild, under Falador.

- **when:** in region 12184
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Mining Guild. The dwarves keep the good rocks behind a door.
- Coal and mithril as far as the pick swings.

### `area-woodcutting-guild`

> Woodcutting Guild, Hosidius.

- **when:** in region 6454
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Woodcutting Guild. The axes here never seem to stop.
- Hosidius grows the trees; we take the logs. Fair trade.

### `area-farming-guild`

> Farming Guild.

- **when:** in region 4922
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Farming Guild. Everything here grows bigger than it should.
- Mind where you step. Someone loves every plant in here.

### `area-pest-control`

> Void Knights' Outpost.

- **when:** in region 10537
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- The Void Knights' outpost. The portals never stay shut for long.
- The lander leaves soon. Try to come back with all your limbs.

### `area-barbarian-assault`

> Barbarian Assault.

- **when:** in region 10039
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Barbarian Assault. The Penance don't negotiate.
- Teams win here. Lone heroes get carried out.

### `area-arceuus`

> Arceuus, Great Kourend.

- **when:** in region 6714
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Arceuus. The magic here is old, dark and rather rude.
- The library rearranges itself. The scholars pretend that's fine.

### `area-hosidius`

> Hosidius, Great Kourend.

- **when:** in region 6968
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Hosidius. Half of Kourend eats because of these fields.
- Good soil, better kitchens. I could get used to this.

### `area-shayzien`

> Shayzien, Great Kourend.

- **when:** in region 5944
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Shayzien. Soldiers everywhere and every one of them busy.
- They keep the lizardmen at bay. Someone has to.

### `area-lovakengj`

> Lovakengj, Great Kourend.

- **when:** in region 5947
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Lovakengj. Sulphur in the air, minecarts in every direction.
- Breathe lightly. The forges never cool here.

### `area-piscarilius`

> Port Piscarilius, Great Kourend.

- **when:** in region 7226
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Port Piscarilius. Fresh fish and sticky fingers.
- Half this port needs repairing at any given moment. Job security.

### `area-prifddinas`

> Prifddinas.

- **when:** in region 12894, 12895, 13150, 13151
- **pacing:** priority 40, cooldown 3m, delay 2 ticks, overhead

- Prifddinas. A city grown from crystal. Listen, it sings.
- The elves waited a very long time for this place to stand again.
- Sung into being, every wall of it. Try not to chip anything.

### `return-visit`

> Entering somewhere the session has already seen five times. Region ping-pong along a boundary is damped in the counter itself.

- **when:** returning somewhere AND a 40% roll
- **pacing:** priority 20, cooldown 15m

- Back again, are we?
- We're practically regulars here.
- I could walk this place blind by now.
- Again? There are other places, you know.
- Ah. Here. Of course.
- The entry for this place is mostly ditto marks by now.
- We do keep ending up here.

### `cold-stamp-feet`

> Stamps its feet in the snow. Silent on purpose - a physical tell says more than a line about being cold. Animation 4278 is EMOTE_STAMPFEET, measured from the cache; the regions are ones RegionAudit has confirmed.

- **when:** in region 10553, 8253, 6462 AND standing still 30+ ticks AND a 25% roll
- **does:** animation 4278
- **pacing:** priority 25, cooldown 45s

- *(no lines - animation or effect only)*

### `place-liked`

> Somewhere THIS follower is fond of. The set is rolled once per character from the regions the rule set already has opinions about, then kept for good - so two people do not get the same follower, and the mood stops being weather and becomes a temperament you can learn.

- **when:** feels liked about here AND a 6% roll
- **does:** mood +10; animation 13995
- **pacing:** priority 24, cooldown 1h, overhead

- I do like it here. I don't know why. I just do.
- This is one of my places, this.
- Oh, good. I like this one.
- We should come here more.
- This place agrees with me. I feel better already.
- Good air here. Good everything, really.
- Add this one to the short list.

### `place-disliked`

> The other half of the same coin. A follower with no dislikes has no taste, only enthusiasm.

- **when:** feels disliked about here AND a 6% roll
- **does:** mood -8; animation 13997
- **pacing:** priority 24, cooldown 10m, overhead

- Here again. I've never liked this place.
- Something about this spot puts me off. Always has.
- Can we not stay here long?
- I'd rather be almost anywhere else, if I'm honest.
- This place and I have an understanding. We don't get on.
- The notes on this spot are all in a darker ink.
- Every visit here feels a page too long.
- We're back HERE. Wonderful. I'll keep my coat on.
- This place again. My handwriting gets worse just standing in it.

### `crowded-here`

> Crowd awareness. Six people inside six tiles is a bank, an event, or a queue - all of them worth a word.

- **when:** a 8% roll AND 6+ players within 6
- **does:** animation 2113
- **pacing:** priority 22, cooldown 45m, overhead

- Busy here, isn't it.
- Everyone's turned up at once.
- I can't move for people.
- Is something happening, or is it always like this?
- Half the world's here and the other half's queuing.
- A crowd. My notes prefer them at a distance.
- Mind your pockets. I'm minding mine.
- All these people, and not one of them taking notes.

### `place-earned-dislike`

> A score is a running total of what the rules said moments here were worth, so this only reaches -80 after several bad ones. Priority above place-disliked, which would otherwise win with a milder line.

- **when:** place score 1..-80 AND feels disliked about here AND a 8% roll
- **pacing:** priority 30, cooldown 15m, overhead

- I hate it here, and I've got reasons. You were there for all of them.
- Nothing good has ever happened to us in this place. Nothing.
- Every time we come here, all I think about is leaving.
- The evidence against this place keeps arriving.
- This spot owes us. It knows what it did.
- The file on this place is thick, and none of it flattering.
- Here we are again, where most of our bad luck happens.
- I've counted what this place has cost us. Twice, to be sure.

### `defended-dislike`

> The roll says it hates this place; the earned score says good things keep happening here. feelsAbout already answers 'liked' - the override won - so this is the follower conceding the evidence while keeping the grudge, which is the only moment the temperament is visible as a temperament.

- **when:** rolled disliked about here AND place score 40 or more
- **pacing:** priority 60, cooldown 45m, overhead

- I know. Good things happen here. I still don't like it.
- The ledger says this place is good to us. The ledger doesn't have to stand in it.
- Fine, it's been lucky for us. Lucky and unlovely.

### `defended-like`

> The mirror: rolled fondness against earned bad luck. Loyalty, argued with the record.

- **when:** rolled liked about here AND place score 1..-40
- **pacing:** priority 60, cooldown 45m, overhead

- Nothing but trouble for us here, I know. I like it anyway. Don't ask.
- The record is against this place. I'm not. Loyalty is like that.

### `place-earned-like`

> The other end of the same total.

- **when:** place score 80 or more AND feels liked about here AND a 8% roll
- **pacing:** priority 30, cooldown 45m, overhead

- Good things happen here. I've kept count, and they do.
- I like this place, and for once I can tell you exactly why.
- We've had some luck here. Long may it continue.
- This place's tally is well in the black.
- This spot's earned its good name with me, fair and square.

### `place-remembers`

> What the place itself holds, via markHere on the rules that won those moments. Waits for you to come back to it, which is the whole difference from the incident: that one follows you about.

- **when:** something happened here AND standing still under 500 ticks AND a 10% roll
- **pacing:** priority 32, cooldown 20m, overhead

- This is where {here}. I remember it.
- Same spot. This is where {here}.
- Stand here a second. This is where {here}.
- You may not remember. This is where {here}.
- I've got this spot marked. It's where {here}.
- Here. Right here. This is where {here}.

## errand

*Its little trips: bank, altar, fire, cat, studies, explores.*

### `errand-bank-start`

> The follower heads to a nearby bank booth.

- **when:** errand start: bank
- **pacing:** priority 85, cooldown 1s, overhead

- One second, I need to sort my bank. It's a disaster in there.
- Hold on, quick bank stop. Don't wander off.
- Quick trip to the bank. My gold gets lonely.
- My own bank run, for once. Won't be a moment.
- Two minutes with my vault. It's mostly receipts.

### `errand-bank-end`

> Back from the bank.

- **when:** errand end: bank
- **pacing:** priority 85, cooldown 1s, overhead

- Don't ask how bad it is in there.
- Right, sorted. Ish. Sorted-ish.
- I counted it all. Still not enough.
- All accounted for. Roughly.
- The vault says I'm rich in paper.

### `errand-altar-start`

> The follower heads to a nearby altar.

- **when:** errand start: altar
- **pacing:** priority 85, cooldown 1s, overhead

- Just need a quick word with the gods. Won't be long.
- One prayer, then straight back.
- The altar's right there. It would be rude not to.
- Quick devotions. Hold that thought.
- The gods expect a word when I'm passing.

### `errand-altar-end`

> Back from the altar.

- **when:** errand end: altar
- **pacing:** priority 85, cooldown 1s, overhead

- Right. Sins managed.
- The gods say hello. Probably.
- Prayed for both of us. You're welcome.
- Duty done. Nobody struck me down, so I'll call that a yes.
- Blessed, or near enough.

### `errand-fire-start`

> The follower warms up at a nearby fire.

- **when:** errand start: fire
- **pacing:** priority 85, cooldown 1s, overhead

- Is that a fire? My hands are freezing.
- One minute by the fire. Adventuring is COLD.
- Ooh, a fire. Back in a tick.
- A fire. Getting warm counts as research, I'll have you know.
- That fire's going to waste. Short detour.

### `errand-fire-end`

> Warmed up.

- **when:** errand end: fire
- **pacing:** priority 85, cooldown 1s, overhead

- Better. Carry on.
- Toasty. Off we go, then.
- I regret nothing. My hands agree.
- I can feel my fingers again. Onward.
- Warm now. The quill thanks the fire.

### `errand-firedeath-start`

> The rare fire errand where warming up goes catastrophically wrong.

- **when:** errand start: firedeath
- **does:** mood -6
- **pacing:** priority 85, cooldown 1s, overhead

- AAAAH! HOT! HOT HOT HOT!
- WHY IS IT LIKE THIS!
- MISTAKE! THIS WAS A MISTAKE!
- OW OW OW BAD IDEA!
- THE FIRE IS WINNING!

### `errand-firedeath-end`

> Back from the beyond, with grievances.

- **when:** errand end: firedeath
- **pacing:** priority 85, cooldown 1s, overhead

- That hurt. That REALLY hurt.
- Zero out of ten. I wouldn't recommend dying.
- I'm fine. I'm FINE. We speak of this to no one.
- Note to self: fire is hot. Underlined twice.
- We agreed the fire won. Moving on.
- Singed but wiser. Mostly singed.

### `errand-cat-start`

> The follower has seen a cat. This is not negotiable.

- **when:** errand start: cat
- **pacing:** priority 85, cooldown 1s, overhead

- Cat. CAT. One minute.
- Look at the cat. LOOK at it. One second.
- There is a cat and I have priorities.
- Go on without me. Cat.
- That cat needs saying hello to. It's only polite.

### `errand-cat-end`

> The cat has been attended to.

- **when:** errand end: cat
- **pacing:** priority 85, cooldown 1s, overhead

- That was important. You wouldn't understand.
- The cat and I have an understanding now.
- Softest creature in Gielinor. Confirmed.
- That's the cat seen to. Now I can concentrate.
- I'm told I'm a good judge of cats. By cats.

### `errand-bootlace-start`

> The follower stops for a bootlace; no trip, it catches up after.

- **when:** errand start: bootlace
- **pacing:** priority 85, cooldown 1s, overhead

- Bootlace. Go on, I'll catch up.
- Hold on. Boot situation.
- One moment. My boot disagrees with me.
- Lace emergency. Seconds, not minutes.
- My boot's gone rogue. Dealing with it.

### `errand-bootlace-end`

> Bootlace resolved.

- **when:** errand end: bootlace
- **pacing:** priority 85, cooldown 1s, overhead

- Sorted. What did I miss?
- There. Crisis averted.
- Double knots this time. We're safe.
- Boot subdued. After you.
- Laces behaving. For now.

### `errand-document-start`

> The documenting errand: stops where it stands, takes out the quill and the scroll (items 31829 and 31830, transient props) and writes in it (pose 13302, the Write with a quill of the Advanced posing window). The scribe caught doing the job title.

- **when:** errand start: document
- **pacing:** priority 85, cooldown 1s, overhead

- Hold on. This needs writing down.
- One moment. This one's for posterity.
- Stop a second. This goes in the record.
- Wait there. I'm getting the scroll out.
- If I don't write this down now, it never happened.

### `errand-document-end`

- **when:** errand end: document
- **pacing:** priority 85, cooldown 1s, overhead

- There. Recorded for the ages.
- All dated and filed. I do enjoy that bit.
- The record's a little longer now.
- That's in ink now. No taking it back.
- Done. Future scholars, you're welcome.

### `errand-explore-chest-start`

- **when:** errand start: explore-chest
- **pacing:** priority 85, cooldown 1s, overhead

- Hold on. Nobody leaves a chest just sitting there.
- A chest. Probably locked. Going to check anyway.
- One second. That chest and I have business.

### `errand-explore-chest-end`

- **when:** errand end: explore-chest
- **pacing:** priority 85, cooldown 1s, overhead

- Locked. Naturally. Everything good is locked.
- Empty, or as good as. The mystery was better.
- No treasure. The disappointment is character-building.

### `errand-explore-trapdoor-start`

- **when:** errand start: explore-trapdoor
- **pacing:** priority 85, cooldown 1s, overhead

- A trapdoor. I'll just see where it goes. From up here.
- Hold on, trapdoor. I want a look. A careful one.
- A door in the floor. I'm not walking past that.

### `errand-explore-trapdoor-end`

- **when:** errand end: explore-trapdoor
- **pacing:** priority 85, cooldown 1s, overhead

- It goes DOWN. That's all I'm prepared to confirm.
- Didn't open it. Some doors are questions you don't ask.
- Verdict: ominous. Moving on.

### `errand-explore-signpost-start`

- **when:** errand start: explore-signpost
- **pacing:** priority 85, cooldown 1s, overhead

- A signpost. Let's see if it agrees with me.
- Hold on, checking the sign. I like to know my exits.
- That sign might know something I don't. Back in a moment.

### `errand-explore-signpost-end`

- **when:** errand end: explore-signpost
- **pacing:** priority 85, cooldown 1s, overhead

- The sign says what signs say. Places, that way.
- We're where I thought we were. Good sign, that.
- It points four ways at once. Committed to nothing.

### `errand-explore-noticeboard-start`

- **when:** errand start: explore-noticeboard
- **pacing:** priority 85, cooldown 1s, overhead

- A noticeboard. I'll just see what the locals are on about.
- Hold on. Noticeboards are gossip with structure.
- Let me catch up on the local news. Won't be long.

### `errand-explore-noticeboard-end`

- **when:** errand end: explore-noticeboard
- **pacing:** priority 85, cooldown 1s, overhead

- Mostly chores dressed up as adventure. As usual.
- Somebody lost a cat. Somebody always has.
- Nothing for us on the board. The news is we're fine.

### `errand-explore-gravestone-start`

- **when:** errand start: explore-gravestone
- **pacing:** priority 85, cooldown 1s, overhead

- A gravestone. I'll just pay my respects.
- Hold on. Someone should read the old names.
- One moment at the stone. Manners cost nothing.
- A grave. I'll read it and say nothing clever. That's the rule.
- Somebody's under there, and nobody's stopped by in a while.

### `errand-explore-gravestone-end`

- **when:** errand end: explore-gravestone
- **pacing:** priority 85, cooldown 1s, overhead

- Couldn't read the name. Weather got there first.
- Rest easy, whoever. We're just passing.
- Old stone, older story. We can go now.
- Someone cared enough to carve it. That's worth a line in the book.
- Read what I could. Wrote down less. Some things stay theirs.

### `errand-explore-scarecrow-start`

- **when:** errand start: explore-scarecrow
- **pacing:** priority 85, cooldown 1s, overhead

- Is that a scarecrow or is someone standing very still?
- Hold on. I don't trust that scarecrow.
- One second. That scarecrow moved. I'm nearly sure.

### `errand-explore-scarecrow-end`

- **when:** errand end: explore-scarecrow
- **pacing:** priority 85, cooldown 1s, overhead

- It's straw. I checked. It's definitely straw.
- Just a scarecrow. I'll keep it in the corner of my eye all the same.
- Not a person. Relieved and slightly disappointed.

### `errand-explore-web-start`

- **when:** errand start: explore-web
- **pacing:** priority 85, cooldown 1s, overhead

- That's a big web. I want to know HOW big.
- Hold on, I'll just look at that web. From a respectful distance.
- One moment. Whatever made that web wants keeping an eye on.

### `errand-explore-web-end`

- **when:** errand end: explore-web
- **pacing:** priority 85, cooldown 1s, overhead

- Big web. Which means a bigger spider, somewhere about.
- No spider in residence. That's worse, if anything.
- Lovely web. I'll be watching the ceilings for a while.

### `errand-study-anvil-start`

- **when:** errand start: study-anvil
- **pacing:** priority 85, cooldown 1s, overhead

- An anvil. Hold on, they vary more than you'd think.
- One moment. That anvil wants recording.

### `errand-study-anvil-end`

- **when:** errand end: study-anvil
- **pacing:** priority 85, cooldown 1s, overhead

- Dented, honest and load-bearing. I've got a soft spot for that anvil.
- A serviceable anvil, that. I'll give it a paragraph.

### `errand-study-furnace-start`

- **when:** errand start: study-furnace
- **pacing:** priority 85, cooldown 1s, overhead

- A furnace. Hold on, I want the details down.
- Excuse me. I take a professional interest in furnaces.

### `errand-study-furnace-end`

- **when:** errand end: study-furnace
- **pacing:** priority 85, cooldown 1s, overhead

- Hot, dutiful and slightly smug. I know the type.
- It's a good furnace. Posterity will want to know.

### `errand-study-well-start`

- **when:** errand start: study-well
- **pacing:** priority 85, cooldown 1s, overhead

- A well. I'd like to know how deep it goes.
- Hold on. Wells have histories, you know.

### `errand-study-well-end`

- **when:** errand end: study-well
- **pacing:** priority 85, cooldown 1s, overhead

- Deep, damp and dependable. Everything you'd want in a well.
- I didn't look down, mind. I never look down.

### `errand-study-fountain-start`

- **when:** errand start: study-fountain
- **pacing:** priority 85, cooldown 1s, overhead

- A fountain. I always want to know who paid for these.
- One moment. The fountain goes in the book.

### `errand-study-fountain-end`

- **when:** errand end: study-fountain
- **pacing:** priority 85, cooldown 1s, overhead

- Ornamental and faintly leaky. Civic pride usually is.
- The pigeons rate it highly. They'd know.

### `errand-study-statue-start`

- **when:** errand start: study-statue
- **pacing:** priority 85, cooldown 1s, overhead

- A statue. Somebody mattered once. Let's see who.
- Hold on. Statues are just history showing off.

### `errand-study-statue-end`

- **when:** errand end: study-statue
- **pacing:** priority 85, cooldown 1s, overhead

- Taller than they ever were in life, I'll bet.
- The plaque's flattering. They always are.

### `errand-study-bookcase-start`

- **when:** errand start: study-bookcase
- **pacing:** priority 85, cooldown 1s, overhead

- A bookcase. Let's see what they read round here.
- I always look at other people's shelves. Can't help it.

### `errand-study-bookcase-end`

- **when:** errand end: study-bookcase
- **pacing:** priority 85, cooldown 1s, overhead

- I've had a look at the competition. I'm not worried.
- All shelved, none of it read. Typical.

### `errand-study-crate-start`

- **when:** errand start: study-crate
- **pacing:** priority 85, cooldown 1s, overhead

- That crate could hold anything. Duty calls.
- A crate. The record doesn't skip crates.

### `errand-study-crate-end`

- **when:** errand end: study-crate
- **pacing:** priority 85, cooldown 1s, overhead

- Wooden, nailed shut and deeply mysterious. Best crate I've seen all week.
- Wrote down what I could. Its secrets survive another day.

### `errand-glance-start`

> The follower thought it saw something.

- **when:** errand start: glance
- **pacing:** priority 85, cooldown 1s, overhead

- Wait. Did you see that?
- Hold on. Something moved over there.
- Shh. There. No, THERE.
- Hold up. Movement, left side.
- One second. Something caught my eye.

### `errand-glance-end`

> It was nothing.

- **when:** errand end: glance
- **pacing:** priority 85, cooldown 1s, overhead

- ...Never mind. It was a rock.
- Nothing. It was nothing. Probably nothing.
- False alarm. The shadows are shifty today.
- Stand down. It was a bush being a bush.
- As you were. The shadow checked out.

## souvenir

*Wishes, gifts, the pocket, and what it carries.*

### `souvenir-rock`

> Picks something up and carries it about for a while. It only counts if it was SAID - a souvenir nobody was told about is invisible - and it refuses while already carrying something, or the follower silently swaps out an object it has been talking about.

- **when:** standing still 500+ ticks AND standing still under 620 ticks AND NOT (carrying a souvenir) AND a 1% roll
- **does:** picks up 'a nice flat rock' (50 min)
- **pacing:** priority 22, cooldown 30m, overhead

- Hold on. Look at this rock. Perfectly flat.
- I'm taking this rock. It's a good rock and I'll hear nothing else.
- One more rock for the collection. The collection is one rock.
- Flat, grey, dependable. This rock has everything.

### `souvenir-stick`

> Picks something up and carries it about for a while. It only counts if it was SAID - a souvenir nobody was told about is invisible - and it refuses while already carrying something, or the follower silently swaps out an object it has been talking about.

- **when:** standing still 500+ ticks AND standing still under 620 ticks AND NOT (carrying a souvenir) AND a 1% roll
- **does:** picks up 'an interesting stick' (50 min)
- **pacing:** priority 22, cooldown 30m, overhead

- Wait. This stick's interesting. I couldn't tell you why.
- I'm keeping this stick. Don't look at me like that.
- A stick with character. You don't see many.
- This stick has a future. I can't say what kind.

### `souvenir-pebble`

> Picks something up and carries it about for a while. It only counts if it was SAID - a souvenir nobody was told about is invisible - and it refuses while already carrying something, or the follower silently swaps out an object it has been talking about.

- **when:** standing still 500+ ticks AND standing still under 620 ticks AND NOT (carrying a souvenir) AND a 1% roll
- **does:** picks up 'a lucky-looking pebble' (50 min)
- **pacing:** priority 22, cooldown 30m, overhead

- That pebble's lucky. I can tell. Give me a second.
- Finders keepers. This pebble's coming with me.
- Look how smooth this is. I'm having it.
- A pebble like this doesn't come along twice.

### `souvenir-feather`

> Picks something up and carries it about for a while. It only counts if it was SAID - a souvenir nobody was told about is invisible - and it refuses while already carrying something, or the follower silently swaps out an object it has been talking about.

- **when:** standing still 500+ ticks AND standing still under 620 ticks AND NOT (carrying a souvenir) AND a 1% roll
- **does:** picks up 'a feather' (50 min)
- **pacing:** priority 22, cooldown 30m, overhead

- A feather. Right. In the pocket it goes.
- Somebody dropped a feather here. Their loss.
- A feather in decent condition. The day's looking up.
- This feather's too good to leave lying about. I'll take it.

### `souvenir-nail`

> Picks something up and carries it about for a while. It only counts if it was SAID - a souvenir nobody was told about is invisible - and it refuses while already carrying something, or the follower silently swaps out an object it has been talking about.

- **when:** standing still 500+ ticks AND standing still under 620 ticks AND NOT (carrying a souvenir) AND a 1% roll
- **does:** picks up 'a bent nail' (50 min)
- **pacing:** priority 22, cooldown 30m, overhead

- Look, a nail. Bent, but still a nail.
- A bent nail. That'll come in useful one day.
- One bent nail, slightly historic. Mine now.
- You'd be amazed what a nail can fix. Not this one. Still.

### `wish-feather`

- **when:** NOT (repeating one action 100+ ticks) AND standing still 60+ ticks AND standing still under 500 ticks AND a 4% roll AND NOT (wishing for anything) AND NOT (carrying a souvenir)
- **does:** wishes for feather (45 min)
- **pacing:** priority 24, cooldown 45m, overhead

- I could do with a feather, if one turns up.
- Quills wear out. Keep an eye open for a feather.
- If a bird's been careless anywhere near you, I'd take the feather.
- A feather would do. The old quill's more stump than quill.
- Not to nag, but a feather wouldn't go amiss. For the writing.

### `wish-papyrus`

- **when:** NOT (repeating one action 100+ ticks) AND standing still 60+ ticks AND standing still under 500 ticks AND a 4% roll AND NOT (wishing for anything) AND NOT (carrying a souvenir)
- **does:** wishes for papyrus (45 min)
- **pacing:** priority 24, cooldown 45m, overhead

- Papyrus, if you pass any. Mine's nearly out.
- Running low on papyrus. The good pages go fast.
- The margins are doing overtime. Papyrus, if you see any.
- If you see papyrus, think of me. Then pick it up.
- I write small, but not that small. Papyrus, please.

### `wish-ball-of-wool`

- **when:** NOT (repeating one action 100+ ticks) AND standing still 60+ ticks AND standing still under 500 ticks AND a 4% roll AND NOT (wishing for anything) AND NOT (carrying a souvenir)
- **does:** wishes for ball of wool (45 min)
- **pacing:** priority 24, cooldown 45m, overhead

- A ball of wool would tie these scrolls properly.
- The scrolls keep unrolling. A ball of wool would fix that.
- Wool, if you spot some. My scrolls are currently a loose confederation.
- Not urgent, but a ball of wool would bring some order to the satchel.
- A ball of wool, before this bundle flaps itself to bits.

### `wish-soft-clay`

- **when:** NOT (repeating one action 100+ ticks) AND standing still 60+ ticks AND standing still under 500 ticks AND a 4% roll AND NOT (wishing for anything) AND NOT (carrying a souvenir)
- **does:** wishes for soft clay (45 min)
- **pacing:** priority 24, cooldown 45m, overhead

- Soft clay, if you see any. Seals want stamping.
- I could use soft clay. Official documents deserve a seal.
- Some soft clay would help. Don't ask what for. It's a surprise.
- If any soft clay comes your way, I've got plans for it. Small plans.
- I'm making something and it's going badly. Soft clay would save it.

### `wish-charcoal`

- **when:** NOT (repeating one action 100+ ticks) AND standing still 60+ ticks AND standing still under 500 ticks AND a 4% roll AND NOT (wishing for anything) AND NOT (carrying a souvenir)
- **does:** wishes for charcoal (45 min)
- **pacing:** priority 24, cooldown 45m, overhead

- Charcoal, if any crosses your path. For the rough sketches.
- A bit of charcoal would be welcome. Some things get drawn, not written.
- If charcoal turns up, I'll take it. The sketches are getting faint.
- Somebody has to draw the crates. I'll need charcoal for that.
- A stick of charcoal would do. I've been sketching with a burnt twig.

### `gifted-accept`

> The player picked 'Found you that <wish>.' AND the thing is really in their bag - checked, because play testing produced the bluff within minutes. Nothing is consumed (client-side), but presence is honest.

- **when:** the player answering 'gift' AND wishing for anything AND the wished item in the bag AND NOT (carrying a souvenir)
- **does:** mood +10; spends the wish; picks up 'the {wish} you found me' (90 min)
- **pacing:** priority 50, cooldown 10s, delay 2 ticks, overhead

- That {wish}. Exactly what I needed.
- The {wish}! You remembered. I won't forget that.
- Perfect. The {wish} goes in the good pocket.
- You actually found the {wish}. The ledger tips your way.
- My old {wish} for your new one. You come out even, which is how I like it.
- Take my worn {wish} in exchange. It's got years left in it.
- A trade rather than a gift. You keep my old {wish}, I keep yours.
- The {wish} is mine now and my old one's yours. Nobody's poorer.
- Here, have my tired old {wish}. It's still good for something.
- Even trade, {wish} for {wish}. You'll hardly notice, but I very much will.
- I took the fresh {wish} and left you my old one. That's only manners.
- You've still got my old {wish}. I'm not in the business of leaving you short.
- The old {wish} is yours. Don't look at it too closely.
- Thank you. My worn {wish} is yours, if you want it.

### `gifted-swap`

> The wish granted while the pocket already holds a souvenir: the follower trades up, out loud. A gift outranks a rock it found itself, and a SPOKEN swap is honest where a silent one was the thing the old deferral rule existed to prevent. Deferring instead parked the option for the length of a ninety-minute carry, which play testing read as the option refusing to go away.

- **when:** the player answering 'gift' AND wishing for anything AND the wished item in the bag AND carrying a souvenir
- **does:** mood +10; spends the wish; trades for 'the {wish} you found me' (90 min)
- **pacing:** priority 60, cooldown 10s, delay 2 ticks, overhead

- The {wish}! I'll set down {souvenir} to make room.
- A trade, then: {souvenir} out, the {wish} in.
- The {wish} outranks {souvenir}. Out it goes.
- Out goes {souvenir}, in goes the {wish}, and you get my old one.
- The {wish} bumps {souvenir}. Have my worn one, so you're not short.

### `gifted-bluff`

> The empty-bag claim, already caught IN the dialog box - the script branches on the bag, because a neutral box read as the follower not looking. This is the dry coda afterwards, not a second telling-off, so it needs no {wish} and no bag re-check.

- **when:** the player answering 'bluff'
- **does:** mood -2
- **pacing:** priority 70, cooldown 30s, delay 2 ticks, overhead

- That's going on your record, you know.
- Filed under: nice try.

### `gifted-slipped`

> The mid-box race: the bag had the thing when the box opened and not when the branch was picked. The box already said 'that is the one', so silence here would read as the follower being fooled - and this follower checks.

- **when:** the player answering 'gift' AND wishing for anything AND NOT (the wished item in the bag)
- **pacing:** priority 65, cooldown 10s, delay 2 ticks, overhead

- Where did the {wish} go? It was in there a moment ago.
- You had it just now. I watched the bag. Bring it back.

### `gifted-late`

> The race: the wish lapsed between the box opening and the click. Rare, but silence there reads as a bug.

- **when:** the player answering 'gift' AND NOT (wishing for anything)
- **does:** mood +2
- **pacing:** priority 40, cooldown 10s, delay 2 ticks, overhead

- Kind of you. The moment's rather passed, but kind of you.
- I'd stopped hoping for that one. Appreciated all the same.

### `souvenir-mention`

> Brings up the thing it is holding. This is the payoff for the object existing at all: it is the same rock forty minutes later.

- **when:** carrying a souvenir AND standing still 100+ ticks AND standing still under 600 ticks AND a 2% roll
- **pacing:** priority 20, cooldown 20m, overhead

- I've still got {souvenir}, in case you were wondering.
- Still carrying {souvenir}. No regrets.
- There's {souvenir} in my pocket and you've not asked about it once.
- I'm looking after {souvenir}. It's safe and well.
- Me and {souvenir}, still going strong.
- I've carried {souvenir} through some things today.
- I've grown attached to {souvenir}. Don't tell anyone.
- I'm keeping {souvenir}, and that's not up for discussion.

### `souvenir-lost`

> And one day it is not the same rock. The name survives the loss so the line can mourn it properly.

- **when:** the souvenir lost
- **does:** mood -6
- **pacing:** priority 36, cooldown 5s, overhead

- I've lost {souvenir}. Somewhere back there.
- I had {souvenir} and now I don't. I'd rather not talk about it.
- Right, well. That's {souvenir} gone, then.
- A moment of silence for {souvenir}.
- We're down {souvenir}. Somewhere muddy, probably.
- Gone. I didn't do right by {souvenir}.

## bet

*Its predictions about drops.*

### `bet-rich`

> The optimist. Same machinery, other direction, so being wrong is on the table for both of them.

- **when:** a fight starting AND NOT (a bet open) AND a 12% roll
- **does:** bets on a rich drop
- **pacing:** priority 28, cooldown 4m, overhead

- I've got a feeling about this one. A good feeling.
- This is the one. Don't ask me how I know.
- Mark this fight. Something's coming off it.
- Next drop's a good one. Put it in writing. I just did.
- Call it instinct. This one pays.
- Today's the day the luck pays out. You heard it here first.
- I've done the arithmetic on this one. It's owed.
- Watch this next bit. I've gone on record.

### `bet-won`

> Above the loot rules so the verdict on the prediction beats the remark about the prize. The follower called it; that is the story.

- **when:** the bet won
- **does:** mood +10; animation 2109; holds still
- **pacing:** priority 72, cooldown 5s, overhead

- Told you.
- I said. I did say.
- And that's why you listen to me.
- Called it. Not a word of thanks, mind.
- As predicted. The book never lies.
- Right again. Try to look surprised.

### `bet-lost`

> The half that makes the other half worth anything.

- **when:** the bet lost
- **does:** mood -4
- **pacing:** priority 72, cooldown 5s, overhead

- I was wrong. Enjoy it, it doesn't happen often.
- Well. Ignore everything I said.
- That's not what I predicted. Moving on.
- We'll not speak of this again.
- The prediction business is harder than it looks.
- Strike that entry. Carefully, so nobody can read it.

## clock

*The hour and how long we have been at it.*

### `clock-small-hours`

> minimum and maximum are hours, and the comparison wraps past midnight, so this window is written the way a person says it.

- **when:** time of day (any) AND standing still 100+ ticks AND a 10% roll
- **pacing:** priority 24, cooldown 45m, overhead

- You do know what time it is.
- It's the small hours. I'm not judging. I'm noticing.
- Everyone sensible's asleep. I'm nearly one of them.
- The birds'll start up soon. Just so you know.
- The candle's low. Metaphorically. Go to bed.
- Night watchmen keep better hours than this.
- The moon and I think you should stop soon.

### `clock-late-and-long`

> The two facts together, which is a different remark from either of them alone: it is late AND we have been at this for hours.

- **when:** time of day (any) AND session minute 180+ AND standing still 60+ ticks AND a 20% roll
- **pacing:** priority 30, cooldown 1h, overhead

- Whatever this is, it'll still be here tomorrow.
- This late, and this long at it. Go to bed. I'll keep watch.
- I'm not going to stop you. I am going to keep mentioning it.
- Long day, late hour. The maths says rest.
- Even the ink wants to sleep.
- Tomorrow exists. I've seen the schedule.

### `clock-morning`

> Early, which is either admirable or the other thing.

- **when:** time of day (any) AND standing still 100+ ticks AND a 10% roll
- **pacing:** priority 24, cooldown 45m, overhead

- Up early, or not been to bed? I won't ask.
- This is a respectable hour to be out. Barely.
- Morning. Whichever side of it you're on.
- Morning light. Good for reading, better for walking.
- The day's fresh. Let's not waste it standing about.
- Dawn patrol, is it? I'm awake. Mostly.

### `been-at-it-two-hours`

> Pinned with a maximum so the two-hour line does not keep being true at four hours and shout over the four-hour one.

- **when:** session minute 120-179 AND standing still 60+ ticks AND a 25% roll
- **pacing:** priority 26, cooldown 1h, overhead

- Two hours. I checked. Twice.
- We've been at this two hours. I've enjoyed most of it.
- Two hours in. The entry just says 'continued'.
- That's two hours. My feet filed the first complaint.
- Past the two-hour mark. You're nothing if not consistent.

### `been-at-it-all-day`

> Five hours. Said once, and meant.

- **when:** session minute 300+ AND standing still 60+ ticks AND a 30% roll
- **pacing:** priority 28, cooldown 2h, overhead

- Five hours. Stand up, walk about. I'll still be here.
- That's most of a day, that is. Have you eaten?
- Five hours together. I've seen marriages with less.
- A full day's work and then some. My quill's gone soft.
- The sun's done most of an arc and neither of us noticed.
- Whole chapters have passed. Stretch, at least.

### `clock-new-month`

> The first of every month, on the player's own calendar - day without month means every month. The scribe's favourite day.

- **when:** dateis AND standing still 20+ ticks
- **pacing:** priority 34, cooldown 6h, overhead

- New month. Fresh page. Let's fill this one with better decisions.
- First of the month. I've ruled the columns already.
- A new month. The old one's filed under done.

### `clock-christmas`

> The 24th through the 26th of December, player's own calendar. Once per session at most; the cooldown keeps a relog from repeating it same day.

- **when:** dateis AND standing still 20+ ticks
- **does:** occasion (never held back)
- **pacing:** priority 62, cooldown 6h, overhead

- Christmas. I got you nothing. I wrote you in, though.
- Merry Christmas. The ledger takes the day off. I don't.

### `clock-new-year`

> New Year's Eve into New Year's Day, wrapping past the year end the way the late-night clock window wraps past midnight.

- **when:** dateis AND standing still 20+ ticks
- **does:** occasion (never held back)
- **pacing:** priority 62, cooldown 6h, overhead

- A new year. Next volume, same author.
- The year's turning. I'll be mis-dating entries for a month.

### `clock-halloween`

> The 31st of October. The scarecrow canon gets its day.

- **when:** dateis AND standing still 20+ ticks
- **does:** occasion (never held back)
- **pacing:** priority 62, cooldown 6h, overhead

- All Hallows. Every scarecrow tonight is a suspect.
- Halloween. If the graveyard moves, I'm not recording it.

## gear

*What you put on.*

### `gear-staves`

> Equip reactions fire once per equip (rising edge). Ids: battlestaff, lava battlestaff, air/water/earth/fire staves.

- **when:** equipping 1391, 3053, 1381, 1383, 1385, 1387
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Runes at the ready, then. Try not to singe my outfit.

### `gear-dfs`

- **when:** equipping 11283
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Is that dragon breath swirling in there? Keep it pointed away from me.

### `gear-rogue`

- **when:** equipping 5553, 5555, 5556, 5557
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Dressed for crime, I see. I saw nothing.

### `gear-dodgy-necklace`

- **when:** equipping 21143
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A dodgy necklace? Sensible, in your line of work.

### `gear-dragon-dagger`

> Dragon dagger, incl. poisoned and arena variants.

- **when:** equipping 1215, 1231, 5680, 5698, 20407, 28019, 28021, 28023, 28025
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The dragon dagger. Zew Zeww, does it.
- Ah, the classic. Stab twice, ask questions never.
- Is it poisoned? Don't tell me. Just keep it pointed that way.

### `gear-granite-maul`

> Granite maul, all variants.

- **when:** equipping 4153, 12848, 20557, 24225, 24227
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The granite maul. No warning, just bonk.
- Subtlety's overrated anyway.

### `gear-dragon-warhammer`

> Dragon warhammer, incl. ornament variants.

- **when:** equipping 13576, 20785, 26710, 28035
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- One good smack and their armour turns to paper.
- Please land it. For morale.

### `gear-elder-maul`

> Elder maul, incl. ornament variant.

- **when:** equipping 21003, 21205, 27100
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- That's not a weapon, that's a landmark.
- Lift with the knees. Both of them.

### `gear-godsword`

> Any godsword: Armadyl, Bandos, Saradomin, Zamorak, Ancient, incl. ornament variants.

- **when:** equipping 11802, 11804, 11806, 11808, 20368, 20370, 20372, 20374, 20593, 22665, 26233, 27184, 28537, 29605
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A godsword. Do warn me before you swing.
- That blade is taller than I am.
- The gods made that. Respect it, from a distance.

### `gear-dragon-claws`

> Dragon claws, incl. ornament variants.

- **when:** equipping 13652, 20784, 26708, 28039
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Dragon claws. Four hits, and not one of them polite.
- Very sharp. I'll keep my hands in my pockets.

### `gear-abyssal-tentacle`

> Abyssal tentacle, incl. ornament variant.

- **when:** equipping 12006, 26484
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A whip with a squid on it. Why not.
- It's wriggling. It's WRIGGLING.

### `gear-toxic-blowpipe`

> Toxic blowpipe, charged or empty.

- **when:** equipping 12924, 12926
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Darts and venom. Horribly efficient.
- Just don't inhale.

### `gear-twisted-bow`

> Twisted bow.

- **when:** equipping 20997
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- THE bow. We're dangerous AND rich.
- Careful where you lean that. It costs more than a castle.

### `gear-bofa`

> Bow of faerdhinen, incl. corrupted and recolours.

- **when:** equipping 25862, 25865, 25867, 25884, 25886, 25888, 25890, 25892, 25894, 25896, 27187, 33021
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- It sings every time you draw it. Fancy.
- Elven craftsmanship. We're posh now.

### `gear-scythe`

> Scythe of vitur, incl. holy and sanguine variants.

- **when:** equipping 22325, 22486, 22664, 25736, 25738, 25739, 25741, 28543, 28545
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Mind the arc, I walk behind you, remember.
- A vampyre's scythe. I'd love to know whose it was.

### `gear-ghrazi-rapier`

> Ghrazi rapier, incl. holy variant.

- **when:** equipping 22324, 23628, 25734
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The pointy end goes in very fast, I take it.
- Elegant, deadly, and it knows it.

### `gear-osmumtens-fang`

> Osmumten's fang, incl. ornament variant.

- **when:** equipping 26219, 27246, 33174
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The fang rarely misses. The pharaohs knew things.
- A pyramid's worth of history on your belt.

### `gear-voidwaker`

> Voidwaker.

- **when:** equipping 27690, 27869, 29607
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- That glow isn't friendly. Neither are you, apparently.
- Three Wilderness bosses each gave up a piece for that. Generous of them.

### `gear-rune-crossbow`

> Rune crossbow, incl. ornament variant.

- **when:** equipping 9185, 23601, 26486
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Ah, old reliable. I do like the click it makes.
- Load, loose, repeat. Some things don't need improving.

### `gear-magic-shortbow`

> Magic shortbow and imbued version.

- **when:** equipping 861, 12788, 20558
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Bends like a reed, bites like a wolf.
- Twang twang twang. Music to my ears.

### `gear-trident`

> Tridents of the seas and swamp, all variants.

- **when:** equipping 11905, 11907, 12899, 22288, 22292, 33314, 33318, 33322, 33323, 33326
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A staff that casts itself. Lazy, but it works.
- The kraken parted with that grudgingly, I'd wager.

### `gear-tumekens-shadow`

> Tumeken's shadow.

- **when:** equipping 27275, 27277
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A staff of pure shadow. I'd rather it didn't notice me.
- It hums with the sun god's power. Sounds expensive.

### `gear-dinhs-bulwark`

> Dinh's bulwark.

- **when:** equipping 21015
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Is there room behind that for both of us?
- A door with ambitions.

### `gear-bandos-armour`

> Bandos chestplate or tassets, incl. ornament variants.

- **when:** equipping 11832, 11834, 23646, 26718, 26719
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Bandos armour. You could stop a battering ram in that.
- Plate fit for the Big High War God. He'll want it back.

### `gear-armadyl-armour`

> Armadyl chestplate or chainskirt, incl. ornament variants.

- **when:** equipping 11828, 11830, 26715, 26716
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Feathered and fierce. Kree'arra's tailor does good work.
- Light as a feather. The price isn't.

### `gear-ancestral`

> Any ancestral piece: hat, robe top, robe bottom.

- **when:** equipping 21018, 21021, 21024, 25518, 27193, 27194
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- There's more old magic in those robes than in most libraries.
- Very stylish. Very purple. Very expensive.

### `gear-justiciar`

> Any justiciar piece: faceguard, chestguard, legguards.

- **when:** equipping 22326, 22327, 22328
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Justiciar plate. You look like the law itself.
- They'll need a bigger weapon.

### `gear-black-dhide`

> Black d'hide body or chaps, incl. trimmed variants.

- **when:** equipping 2497, 2503, 12381, 12383, 12385, 12387, 20423, 20424, 25493
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Dragonhide. Practical AND menacing.
- Some dragon is very cold right now.

### `gear-fighter-torso`

> Fighter torso, incl. ornament variants.

- **when:** equipping 10551, 24175, 28067, 28069
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The torso! That took a fair bit of Penance.
- Blood, sweat and Barbarian Assault.

### `gear-barrows-gloves`

> Barrows gloves.

- **when:** equipping 7462, 23593, 27112
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Barrows gloves. The Culinaromancer sends his regards.
- The best gloves going, and all it took was rescuing a dinner party.

### `gear-dragon-defender`

> Dragon defender, incl. trimmed variants.

- **when:** equipping 12954, 19722, 23597, 24143, 27008
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Attack and defence in one fist. The cyclopes paid for that.
- How many cyclopes was it, in the end?

### `gear-avernic-defender`

> Avernic defender.

- **when:** equipping 22322, 24186
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- An avernic defender. The cyclopes could never.
- From the Theatre of Blood. I won't ask what it cost you.

### `gear-crystal-armour`

> Crystal helm, body or legs, incl. recolours. Gauntlet versions excluded.

- **when:** equipping 23971, 23973, 23975, 23977, 23979, 23981, 27697, 27699, 27701, 27703, 27705, 27707, 27709, 27711, 27713, 27715, 27717, 27719, 27721, 27723, 27725, 27727, 27729, 27731, 27733, 27735, 27737, 27739, 27741, 27743, 27745, 27747, 27749, 27751, 27753, 27755, 27757, 27759, 27761, 27763, 27765, 27767, 27769, 27771, 27773, 27775, 27777, 27779, 33023, 33025, 33027, 33029, 33031, 33033, 33166, 33168, 33170
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Crystal armour. You chime when you walk now.
- Elegant. Just don't drop it.

### `gear-cerberus-boots`

> Primordial, pegasian or eternal boots.

- **when:** equipping 13235, 13237, 13239, 23644
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Boots off Cerberus. I'd check them for dog hair.
- Fancy footwear. Mind the puddles.

### `gear-dragon-boots`

> Dragon boots, incl. gilded variant.

- **when:** equipping 11840, 22234, 28055
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Dragon boots. Kicking above your weight.
- Stomp responsibly.

### `gear-ranger-boots`

> Ranger boots.

- **when:** equipping 2577
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Ranger boots. A fortune, on your feet.
- Clue scroll boots. Somebody dug a lot of holes for those.

### `gear-fury`

> Amulet of fury, incl. ornament variant.

- **when:** equipping 6585, 12436, 23640
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A fury. Good at everything, which is a bit much for an amulet.
- Onyx. The good stuff.

### `gear-torture`

> Amulet of torture, incl. ornament variant.

- **when:** equipping 19553, 20366, 27173
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- An amulet that bites back. Bold choice.
- Torture, worn voluntarily. You lot are strange.

### `gear-anguish`

> Necklace of anguish, incl. ornament variant.

- **when:** equipping 19547, 22249, 27172
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Anguish. The name really sells it.
- Zenyte on a chain. Mind the muggers.

### `gear-occult`

> Occult necklace, incl. ornament variant.

- **when:** equipping 12002, 19720, 23654
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The occult necklace whispers. Ignore it.
- Powerful, yes. Also deeply unsettling.

### `gear-glory`

> Amulet of glory, any charge, incl. trimmed variants.

- **when:** equipping 1704, 1706, 1708, 1710, 1712, 10354, 10356, 10358, 10360, 10362, 11964, 11966, 11976, 11978, 20586
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- An amulet of glory. You can't beat a classic.
- Straight to Edgeville, then?

### `gear-set-graceful`

> Full graceful outfit: all six pieces, any recolour mix.

- **when:** equipping 11850, 11851, 13579, 13580, 13591, 13592, 13603, 13604, 13615, 13616, 13627, 13628, 13667, 13668, 21061, 21063, 24743, 24745, 25069, 25071, 27444, 27446, 30045, 30047 AND equipping 11852, 11853, 13581, 13582, 13593, 13594, 13605, 13606, 13617, 13618, 13629, 13630, 13669, 13670, 21064, 21066, 24746, 24748, 25072, 25074, 27447, 27449, 30048, 30050 AND equipping 11854, 11855, 13583, 13584, 13595, 13596, 13607, 13608, 13619, 13620, 13631, 13632, 13671, 13672, 21067, 21069, 24749, 24751, 25075, 25077, 27450, 27452, 30051, 30053 AND equipping 11856, 11857, 13585, 13586, 13597, 13598, 13609, 13610, 13621, 13622, 13633, 13634, 13673, 13674, 21070, 21072, 24752, 24754, 25078, 25080, 27453, 27455, 30054, 30056 AND equipping 11858, 11859, 13587, 13588, 13599, 13600, 13611, 13612, 13623, 13624, 13635, 13636, 13675, 13676, 21073, 21075, 24755, 24757, 25081, 25083, 27456, 27458, 30057, 30059 AND equipping 11860, 11861, 13589, 13590, 13601, 13602, 13613, 13614, 13625, 13626, 13637, 13638, 13677, 13678, 21076, 21078, 24758, 24760, 25084, 25086, 27459, 27461, 30060, 30062
- **pacing:** priority 35, cooldown 5s, delay 2 ticks, overhead

- Full graceful. Off to run laps, are we?
- Marks well spent. Very aerodynamic.
- All that grace, and I still have to walk.

### `gear-set-void`

> Full void: any helm plus top, robe and gloves. Elite counts.

- **when:** equipping 11663, 11664, 11665, 24183, 24184, 24185, 26473, 26475, 26477, 27005, 27006, 27007 AND equipping 8839, 13072, 24177, 24178, 26463, 26469, 27000, 27003 AND equipping 8840, 13073, 24179, 24180, 26465, 26471, 27001, 27004 AND equipping 8842, 24182, 26467, 27002
- **pacing:** priority 35, cooldown 5s, delay 2 ticks, overhead

- Full void. The pests fear you.
- All those trips to the Void Knights' outpost finally paid off.

### `gear-set-dharok`

> Full Dharok's: helm, platebody, platelegs and greataxe, any repair state.

- **when:** equipping 4716, 4880, 4881, 4882, 4883, 4884, 23639 AND equipping 4720, 4892, 4893, 4894, 4895, 4896, 25515 AND equipping 4722, 4898, 4899, 4900, 4901, 4902, 23633 AND equipping 4718, 4886, 4887, 4888, 4889, 4890, 25516
- **pacing:** priority 35, cooldown 5s, delay 2 ticks, overhead

- Full Dharok's, so I'm meant to want you hurt. I don't.
- The lower your health, the harder you hit. I hate the maths.

### `gear-set-rune`

> Full rune: full helm, platebody, platelegs and kiteshield. Trimmed and heraldic count.

- **when:** equipping 1163, 2619, 2627 AND equipping 1127, 2615, 2623, 20421 AND equipping 1079, 2617, 2625, 20422 AND equipping 1201, 2621, 2629, 8714, 8716, 8718, 8720, 8722, 8724, 8726, 8728, 8730, 8732, 8734, 8736, 8738, 8740, 8742, 8744
- **pacing:** priority 35, cooldown 5s, delay 2 ticks, overhead

- Full rune! Every squire's dream.
- Gilded next, I assume?

### `gear-set-bronze`

> Full bronze: full helm, platebody, platelegs and kiteshield.

- **when:** equipping 1155 AND equipping 1117 AND equipping 1075 AND equipping 1189
- **pacing:** priority 35, cooldown 5s, delay 2 ticks, overhead

- Full bronze. A bold fashion statement.
- Ah, bronze. The metal of new beginnings.

### `gear-infernal-cape`

> Infernal cape.

- **when:** equipping 21295, 21297, 23622, 24224
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- An infernal cape. I'm not standing near that.
- Zuk lost. The cape remembers.

### `gear-fire-cape`

> Fire cape.

- **when:** equipping 6570, 10566, 24223
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- That cape still smells of smoke.
- Sixty-three waves for that. Worth it.

### `gear-max-cape`

> Max cape, every variant.

- **when:** equipping 13280, 13329, 13331, 13333, 13335, 13337, 13342, 20760, 21186, 21284, 21285, 21776, 21780, 21784, 21898, 24133, 24134, 24135, 24232, 24233, 24234, 24855, 27363, 27365, 28902, 28906
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A max cape. I'm following a legend.
- Ninety-nine in everything. I've run out of ways to be impressed.

### `gear-quest-cape`

> Quest point cape, incl. trimmed.

- **when:** equipping 9813, 13068
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Every quest done. Gielinor thanks you.
- The cape of having seen everything.

### `gear-skillcape`

> Any skillcape, trimmed or not.

- **when:** equipping 9747, 9748, 9750, 9751, 9753, 9754, 9756, 9757, 9759, 9760, 9762, 9763, 9765, 9766, 9768, 9769, 9771, 9772, 9774, 9775, 9777, 9778, 9780, 9781, 9783, 9784, 9786, 9787, 9789, 9790, 9792, 9793, 9795, 9796, 9798, 9799, 9801, 9802, 9804, 9805, 9807, 9808, 9810, 9811, 9948, 9949, 13340, 13341
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Ninety-nine of something! I'm travelling with an expert.
- A skillcape. Mastery looks good on you.

### `gear-avas`

> Ava's attractor, accumulator or assembler.

- **when:** equipping 10498, 10499, 22109, 23609, 24222
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Ava's finest. Mind the magnetism, I have metal bits.
- It fetches your arrows back. Better trained than most dogs.

### `gear-partyhat`

> Any partyhat.

- **when:** equipping 1038, 1040, 1042, 1044, 1046, 1048, 2422, 11862, 11863, 12399, 27828
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A PARTYHAT? Careful. Half of Gielinor wants that.
- A paper crown worth a kingdom. Only in Gielinor.
- Don't sneeze.

### `gear-santa-hat`

> Santa hat, incl. black and inverted.

- **when:** equipping 1050, 13343, 13344, 21859
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A santa hat. Festive, and worth a small village.
- Ho ho... hang on, it isn't even snowing.

### `gear-halloween-mask`

> Any halloween mask.

- **when:** equipping 1053, 1055, 1057, 11847
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A halloween mask. I'll admit I nearly jumped.
- Scary face, scarier price.

### `gear-slayer-helmet`

> Slayer helmet, every colour and imbue.

- **when:** equipping 11864, 11865, 19639, 19641, 19643, 19645, 19647, 19649, 21264, 21266, 21888, 21890, 23073, 23075, 24370, 24444, 25177, 25179, 25181, 25183, 25185, 25187, 25189, 25191, 25898, 25900, 25902, 25904, 25906, 25908, 25910, 25912, 25914, 26674, 26675, 26676, 26677, 26678, 26679, 26680, 26681, 26682, 26683, 26684, 29816, 29818, 29820, 29822, 33066, 33068, 33070, 33072, 33338, 33340, 33439, 33441, 33443, 33445, 33447, 33449
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The slayer helmet. Duradel would be proud.
- All those monster parts in one hat. Efficient.

### `gear-pickaxe`

> Dragon, infernal, crystal or 3rd age pickaxe.

- **when:** equipping 11920, 12797, 13243, 13244, 20014, 23677, 23680, 23682, 23863, 25063, 25369, 25376, 30345, 30346, 30351
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A fine pick. The rocks don't stand a chance.
- Off to bully some rocks, I see.

### `gear-axe`

> Dragon, infernal, crystal or 3rd age axe, felling axes too.

- **when:** equipping 6739, 13241, 13242, 20011, 23673, 23675, 23862, 25066, 25371, 25378, 28217, 28220, 28223, 30347, 30348, 30352
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- That axe means a forest is in trouble.
- The trees whisper your name. Fearfully.

### `gear-harpoon`

> Dragon, infernal or crystal harpoon.

- **when:** equipping 21028, 21031, 21033, 23762, 23764, 23864, 25059, 25367, 25373, 30342, 30343, 30349
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A harpoon like that? The fish are holding council as we speak.
- Somewhere, a shark just shivered.

### `gear-whip`

> Abyssal whip, incl. volcanic, frozen and ornament variants.

- **when:** equipping 4151, 12773, 12774, 20405, 26482
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- The whip. Someone means business today.

### `gear-rune-scimitar`

> Rune scimitar, incl. ornament variants.

- **when:** equipping 1333, 20402, 23330, 23332, 23334
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- Rune scimmy. The people's blade.

### `gear-dragon-scimitar`

> Dragon scimitar, incl. ornament variant.

- **when:** equipping 4587, 20000, 20406, 28031
- **pacing:** priority 30, cooldown 5s, delay 2 ticks, overhead

- A dragon scimitar. Sixty Attack and a dream.

### `bag-nearly-full`

> A WARNING rather than a report. Two slots left, not none: reacting before is the thing that reads as a mind paying attention, and reacting after is only narration. Counted by walking the container, so it is right whichever way round size() and count() are. In the gear group, which the settings call Items: that is where a player looks for it.

- **when:** 2 or fewer free slots AND NOT (repeating one action 30+ ticks) AND standing still under 100 ticks
- **does:** advises: the bag
- **pacing:** priority 54, cooldown 10m, overhead

- You're nearly out of room, you know.
- Two slots left. Just so you've heard it from someone.
- Bag's about full. Might want to do something about that.
- Bag check: nearly full. That's the whole report.
- You're down to your last two slots.
- The bag's not getting any emptier.
- Almost out of room. I want it on the record that I said so.
- It's getting a bit tight in there.
- Whatever you pick up next had better be worth a slot.
- The bag and I agree: something has to go.
- Nearly full. I'll stop mentioning it eventually.
- How attached are you to everything in there?
- Everything in there was essential at the time.
- You're two items from a difficult decision.
- That bag has ambitions above its size.
- Consider this the polite warning. The bag won't give one.
- I'm keeping a list of what you won't part with.
- The bank, then? I'll allow it this once.
- There's space for two more mistakes.
- Something in there will have to become a memory.
- The bag's at the stage where I'd start choosing.
- The ground is always an option. A poor one.
- I'd hate for the next good thing to have nowhere to go.
- Shall I pretend I didn't see the bag?

## boss

*Bosses sighted and fought.*

### `boss-spawn-inferno-wave`

> Wildcard example: every Inferno NPC name starts with Jal-.

- **when:** Jal-* appearing
- **pacing:** priority 70, cooldown 30s

- Another {npc}. Lovely.
- {npc} incoming.

### `boss-callisto`

> Callisto and Artio, the great bears of the Wilderness.

- **when:** Callisto, Artio appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The great bear. Mind the shockwave, it throws you about like a rag doll.
- His roar rattles your prayers right off. Re-pray fast.
- Bears don't get this big on their own. Something out here helped.
- They say hunters made him this way. The hunters are not available for comment.
- All claw, all grudge, no hibernation.
- The Wilderness grows everything wrong. Exhibit A, this bear.
- He just wants a hug. Do NOT give him a hug.
- Fur like a fortress and a temper to match.
- Somewhere in there is an ordinary bear having a very long day.
- Paws the size of shields. Mind the paws.

### `boss-venenatis`

> Venenatis and Spindel, the Wilderness spider matriarchs.

- **when:** Venenatis, Spindel appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The spider queen. Pray Magic, her web spit stings worst.
- She drinks your prayer through the webs. Restores, plural.
- Mind the sticky ground. Stuck is dead out here.
- Look at the size of her. I'd hoped the bear was the worst of it.
- Her brood carpets the ground when she calls. Charming family.
- Eight legs, and she knows exactly what to do with all of them.
- The webs came before the ruins. Think about that.
- Every strand out here leads back to her.
- She's patient the way only spiders are.
- If you feel watched, it's the several hundred eyes.

### `boss-vetion`

> Vet'ion and Calvar'ion, the skeletal champions.

- **when:** Vet'ion, Calvar'ion appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Vet'ion. He gets back up once, so you're beating him twice.
- The hellhounds come midway. Down them or juggle them.
- His slam splits the ground open. Don't stand in the cracks.
- The last squire of a dead king, still on duty.
- He mourns a kingdom nobody else remembers.
- Bones held together by loyalty and spite.
- The purple glow means trouble. It doesn't go with anything he's wearing, either.
- Even dead, he keeps his post. Terrifying work ethic.
- He's seen empires fall. Mostly his own.
- Someone should tell him the war's over. I'm not volunteering.

### `boss-scorpia`

> Scorpia beneath the Scorpion Pit.

- **when:** Scorpia appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Scorpia. Her little healers burrow up at half, stomp them fast.
- The sting poisons, of course. She's a scorpion the size of a shed.
- She waits under the pit, in the dark, nursing a grudge. Classic ambush.
- Her offspring guard the sands above. Family business.
- The claws are a distraction. The tail is the argument.
- Chitin like armour plate. Find a gap and hit that.
- She's been down there longer than the pit has had a name.
- Everything about her says do not touch. Adventurers touch anyway.
- The healers love her. It's almost sweet. Stop them.
- Some things scuttle. She looms AND scuttles.

### `boss-chaos-elemental`

> The Chaos Elemental near the Rogues Castle.

- **when:** Chaos Elemental appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Chaos Elemental. It pulls your gear right off you, so bring less of it.
- It teleports you about for fun. Its fun, specifically.
- Every attack style, no pattern. Hence the name.
- Born of the Wilderness's own broken magic, they say.
- It ate a shipwreck once. Probably. Who'd check?
- The tentacles reach from somewhere else entirely.
- Don't try to make sense of it. That's how it gets you.
- Chaos isn't its weapon. Chaos is its hobby.
- It's what raw magic dreams about when nobody's casting.
- If your boots vanish, you were warned.

### `boss-chaos-fanatic`

> The Chaos Fanatic on the Wilderness cliffs.

- **when:** Chaos Fanatic appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Fanatic. His blasts scatter wide, sidestep, always sidestep.
- He giggles before the big one. Listen for the giggle.
- A wizard who stared into the Wilderness too long. It stared back.
- He's shouting at things that aren't there. Mostly.
- Once a scholar, they say. The robes were nicer then.
- His aim is bad. His volume is excellent.
- Magic without a licence, hygiene or plan.
- The staff was a fine one, decades and a bath ago.
- Pity him later. Dodge him now.
- Every hermit has a story. His has explosions.

### `boss-crazy-arch`

> The Crazy Archaeologist among the Wilderness ruins.

- **when:** Crazy Archaeologist appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Archaeologist. When he shouts about books, MOVE. The pages explode.
- He throws his research at you. Most of it's explosive.
- He dug up something he really shouldn't have.
- Those ruins were a dig site. Now they're a warning.
- Respect the fedora. Dodge the fedora's owner.
- Somewhere between scholar and hazard. Leaning hazard.
- His colleagues retired. He... specialised.
- History buff, emphasis on the buffeting.
- The artefacts scream, he says. He's not entirely wrong out here.
- Field work does things to a person.

### `boss-kbd`

> The King Black Dragon.

- **when:** King Black Dragon appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The KBD. Three heads and four kinds of breath, so antifire AND prayer.
- Shock, ice, poison, fire. He's a full hazard chart with wings.
- The original terror. Respect your elders.
- Kings come and go. This one has a lair and a legend.
- Three heads means three opinions, all about eating you.
- Adventurers measured themselves against him for generations.
- The lair smells of ash and old victories. His.
- Half the black dragonhide in Gielinor started out down here.
- He still carries himself like the most feared thing out here.
- Bow before the king. Then loose the bow.

### `boss-corp`

> The Corporeal Beast.

- **when:** Corporeal Beast appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Corp. Use a spear, or half of every hit bounces off that hide.
- The dark core leaps out and leeches, knock it away from you.
- It stomps the daydreamers. Stay awake in there.
- It devoured a piece of the Spirit Realm and grew from it.
- The sigils it guards are worth kingdoms, and they're priced like it.
- A beast so vast the cave was dug AROUND it. Probably.
- Its shadow alone would win most fights.
- Bring friends. Bring their friends. Bring spears for all.
- The spirit realm wants it back. The spirit realm can have it.
- Corporeal, as opposed to the ghost it ate. Naming was easy that day.

### `boss-mole`

> The Giant Mole under Falador Park.

- **when:** Giant Mole appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Mole. She digs when hurt, follow the falling dirt.
- Bring a light source and patience. Mostly patience.
- A spade gets you in. Getting through her is up to you.
- Wyson swears the growth potion wasn't his fault.
- Malignius Mortifer's potion, Wyson's garden, and now it's Falador's problem.
- She's just a mole. A mole the size of a hay cart.
- The park above has no idea what's under its lawn.
- All she wants is to dig. All you want is her fur. Impasse.
- The tunnels go everywhere, and she dug every one. You have to admire that.
- Whack the mole. The classic, at scale.

### `boss-kq`

> The Kalphite Queen in the desert hive.

- **when:** Kalphite Queen appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Queen. Her first form shrugs off spells and arrows, so use a blade. Then swap.
- She flies at half. New form, new rules, new prayers.
- Her overhead prayers block whole styles. Fight what she isn't blocking.
- The hive mother herself. The workers were the welcome mat.
- Kalphites go back to the old desert gods' scarabs, they say. She's the best of them.
- Two forms, both furious.
- There's a lance made just for her hide. Or her hide for it. I forget.
- The whole hive's built around what she wants.
- Heat up top, angry kalphites underneath. Lovely part of the world.
- Sting, claw, wing. The full royal treatment.

### `boss-sarachnis`

> Sarachnis beneath the Forthos Ruin.

- **when:** Sarachnis appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Sarachnis. She locks you in place, so keep your food close.
- Her spawn arrive in waves. Clear them or drown in legs.
- She alternates styles as her temper rises. Watch the pattern.
- A spider queen under a temple. Someone built ON TOP of that. On purpose.
- The ruin kept its secret well. The secret kept its appetite.
- Old Forthos fell. She stayed.
- Web-wrapped acolytes decorate her lair. Don't join the decor.
- She's been hungry since the temple had a roof.
- The dragons upstairs are the FRIENDLY neighbours here.
- Eight eyes and every one of them found you.

### `boss-scurrius`

> Scurrius, the rat king of the Varrock sewers.

- **when:** Scurrius appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Scurrius. Mind the falling masonry, the sewer roof wasn't built for this.
- His rats nibble at your feet. Clear them out.
- The ratfolk of the sewers crowned themselves a king.
- A giant rat with a crown of scrap. Aspirational, honestly.
- Varrock flushed its problems downstream. They organised.
- Plenty of fighters got their start on him.
- The tail swipe stings more than you'd expect.
- Every sewer has a king. This one has a THRONE.
- The bones make fine trophies. He'd say the same of yours.
- Squeak softly and carry a big everything.

### `boss-zalcano`

> Zalcano in the Prifddinas dungeon.

- **when:** Zalcano appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Zalcano. Weapons mean nothing, it's pickaxes and imbued stone down here.
- Mine, refine, throw. The forge fights for you.
- Dodge the falling rock, she shakes the ceiling when she's cross.
- A demon the elves bound into living stone.
- The crystal city keeps its monster in the basement.
- The elves sealed her in down here and went back to their singing.
- The one fight where mining fast IS the combat style.
- Stone skin, magma heart, zero patience for miners.
- Every swing of the pick offends her personally.
- She throws the floor at you. Efficient, really.

### `boss-araxxor`

> Araxxor in the lair beneath Morytania.

- **when:** Araxxor appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Araxxor. Mind the eggs, they hatch into exactly what you'd fear.
- The acid trail eats boots. Walk around it, not through it.
- Match your prayers to what it throws. It telegraphs, briefly.
- The nightmare with legs. Eight of them.
- Morytania keeps its worst under the ground. Mostly.
- Fresh webs. Whatever spun them is still at home.
- Its brood NEVER stops. Neither should your feet.
- Spiders sense fear. This one farms it.
- The lair breathes. Try not to think about that.
- Somewhere, Sarachnis is proud of her cousin.

### `boss-muspah`

> The Phantom Muspah beneath the Ghorrock ruins.

- **when:** Phantom Muspah appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Muspah. It shifts forms, shift your prayers with it.
- Spikes burst from the ice. Keep those feet moving.
- When it puts its shield up, that's what the darts are for.
- A muspah from Jhallan's dreams. His nightmares, technically.
- It doesn't move like anything that was ever born. Watch the edges of it.
- Ghorrock's ice kept it quiet for an age. You woke it. Well done.
- Part memory, part monster, all hostile.
- The frozen north forgets nothing. This is one of the things it kept.
- It flickers between shapes like it can't decide how to end you.
- Dream logic with claws.

### `boss-nightmare`

> The Nightmare of Ashihama, and Phosani's Nightmare.

- **when:** The Nightmare, Phosani's Nightmare appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Nightmare. She shows you every attack well in advance. They still hurt.
- The totems charge as she sleeps deeper. Feed them.
- When the floor blooms, be elsewhere. The spores are hers.
- Nobody in Slepe wakes up rested. Now you know why.
- She crossed the sea from Ashihama for richer dreams.
- The sisters of Slepe pray over the sleepers. She has other plans for them.
- A whole town sleeps so she can have her dinner.
- Fight in a dream, wake with the loot. Fair trade.
- Her curses rewrite the rules mid-fight. Read fast.
- Sweet dreams are made of this. Regrettably.

### `boss-duke`

> Duke Sucellus in the Ghorrock depths.

- **when:** Duke Sucellus appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Duke. Brew the fumes and wake him woozy, or he wakes YOU.
- The eyes on the walls mean the floor is about to argue.
- Left, right, never in front. He spits worse than he looks.
- A lieutenant of the old empire, frozen mid-command.
- Zaros froze his failures. This one's kept very well.
- The ice did not improve his mood.
- Even asleep he commands the room. Literally. The room attacks.
- The salts and mushrooms are the trick. Chemistry beats bravery here.
- An age on ice and the first thing he does is fight. Committed.
- Wake the Duke gently. There is no gently. Wake him anyway.

### `boss-leviathan`

> The Leviathan in the Scar.

- **when:** The Leviathan appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Leviathan. The lightning marks where the rocks fall. Read the sky.
- Dodge the volleys, the pattern is strict until it isn't.
- When it stirs up the abyssal ones, cut them down fast.
- The Scar kept it fed. The sea kept it secret.
- Something left over from the abyss, with a long time in the dark to grow.
- It circles like it has all the time in the world. It does.
- The Scar's where the gods drown their mistakes. Not all the way, it seems.
- Scales like shield walls. Aim between the walls.
- It remembers the empire that chained it. Fondly? No.
- Big fish, biggest pond, worst temperament.

### `boss-whisperer`

> The Whisperer in the drowned city of the Scar.

- **when:** The Whisperer appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Whisperer. The lantern is your line back from the shadow realm. Mind it.
- When the hush falls, don't be where you were standing.
- Duck into the shadow realm and you'll see what's really coming.
- A siren, drowned in shadow, still singing after a fashion.
- The sunken city listens to her. So do your worst instincts.
- Her song pulls at the edges of you. Hold your shape.
- She's dreadful now. The voice is the only part that stayed lovely.
- The deep dark has a spokesperson.
- What she whispers is worse than what she shouts.
- Cover your ears. Doesn't help. Do it anyway.

### `boss-vardorvis`

> Vardorvis in the Stranglewood.

- **when:** Vardorvis appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Vardorvis. The axes sweep the arena, dance between them, always.
- When his head speaks, refuse it. Firmly.
- The strangler tendrils grab, break them before they squeeze.
- A champion kept alive by the very thing devouring him.
- The Stranglewood grows through him. He'd object if he could.
- He was Zaros's best swordsman once. The wood took the rest.
- He fights the plant and you at once. Respect the effort.
- The swings are his. The will behind them mostly isn't.
- The forest whispers through his teeth.
- Hard not to feel sorry for him. Harder with an axe coming.

### `boss-jad`

> TzTok-Jad atop the Fight Caves.

- **when:** TzTok-Jad appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Jad. Rears up tall, pray Magic. Slams down, pray Ranged. Nothing else matters.
- The healers come at half. Tag them away, don't brawl them.
- Watch how he moves, not your nerves.
- Sixty-two waves were just the queue for this.
- The TzHaar's champion. Everything they name seems to mean fire.
- Born in lava, raised on challengers.
- The fire cape is woven from wins like this.
- It's a chimney with teeth and a schedule.
- One mistake is the whole exam. No pressure.
- The last thing in the Fight Caves. He's been waiting.

### `boss-zuk`

> TzKal-Zuk at the floor of the Inferno.

- **when:** TzKal-Zuk appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Zuk. Stay behind the glowing wall. That's the whole religion of this fight.
- The set spawns have an order. You know the order. Trust the order.
- When the wall stops, feet first, panic second.
- He waited at the Inferno's floor for someone worth burning.
- The TzHaar sealed their deepest fire behind him. He IS the seal.
- Older than the caves, angrier than the lava.
- The infernal cape remembers every wave it cost.
- He doesn't miss, so make sure there's something in the way.
- It's his wall, but you can hide behind it all the same.
- This is the mountain's heart, and it's furious.

### `boss-hunllef`

> The Hunllef in the Gauntlet.

- **when:** Crystalline Hunllef, Corrupted Hunllef appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Hunllef. Four attacks, then it swaps style. Count along.
- The floor patterns kill faster than the beast. Feet first.
- Swap your own style when it prays. It does pray. Rude.
- You're trapped in there with it. It thinks the same about you.
- The Gauntlet feeds it challengers. It's never once been full.
- Everything in that maze was practice. This is the test.
- You go in with crystal tonics and nerve, and not much else.
- It was here before the city sang. It'll outlast the song.
- The elves hardly mention it. I think that's on purpose.
- Prepared beats brave, in there. Be both.

### `boss-olm`

> The Great Olm at the end of the Chambers of Xeric.

- **when:** Great Olm appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Olm. Hands first, the claws hold all the tricks.
- Lightning down the lanes, crystals from above. The head sees everything.
- When the hands cross, so do the rules. Watch for it.
- It slept under Kourend before Kourend had a name.
- Xeric's chambers kept it fed on the ambitious.
- The mountain is its shell. You're in the shell.
- Old, vast, and slow to wake. Awake now, though. You managed that.
- Every raid ends at those claws or not at all.
- It's in no hurry. It's never had to be.
- The olmlet is cuter. Marginally.

### `boss-verzik`

> Verzik Vitur, finale of the Theatre of Blood.

- **when:** Verzik Vitur appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Verzik. Keep a pillar between you and her opening performance.
- When the webs wrap someone, tear them free fast.
- Her final form skips the pleasantries. Save your best for it.
- A vampyre matriarch who hosts a theatre. The show is you.
- Ver Sinhaza cheers for the house. The house is her.
- The nylocas are her orchestra. The melee ones are the brass.
- Blue blood, a big appetite and her own stage. That's Morytania all over.
- The blood keeps the theatre young. Guess whose.
- Take a bow when it's done. She would.
- Curtains for someone. Let's make it her.

### `boss-wardens`

> The Wardens, finale of the Tombs of Amascut.

- **when:** Tumeken's Warden, Elidinis' Warden appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Wardens. Break the obelisk's rhythm or they never slow.
- They're stone until provoked, then divine. So provoke carefully.
- When the floor charges, the safe squares are a promise. Trust them.
- Two wardens, one borrowed god burning inside.
- Tumeken and Elidinis built guardians, not gods. Amascut disagreed.
- The pyramid's last line of defence, and it's a good line.
- The Devourer wears armies like gloves. These are the gloves.
- Old magic and older grudges. Even the sand's ancient here.
- The desert buries its arguments. This one dug out.
- Endure the light show. Then end it.

### `boss-obor`

> Obor, the Hill Titan.

- **when:** Obor appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Obor. He hits like a landslide, keep off the back wall.
- A hill giant that outgrew the hill.
- The giants whisper his name. The whisper is quite loud.
- He settles every argument with that club. He'd call it diplomacy.
- Getting the key was the easy part. You'll see.
- Every hill giant's hero. Low bar, but he cleared it by a mile.
- He keeps his plans simple. Hit you. It works for him.
- The Titan of the hills, undefeated at home. Until.
- His club predates carpentry. Possibly trees.
- Duck. General advice, but especially now.

### `boss-bryophyta`

> Bryophyta, the Moss Titan.

- **when:** Bryophyta appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Bryophyta. Her growths heal her, cut them down first.
- The moss queen of the sewers. Varrock's dampest secret.
- She grows where the city forgot to look.
- Moss giants leave her offerings. Mostly moss.
- Green, patient, and rooted in bad intentions.
- The sewer floor is hers. The walls too. Possibly your boots now.
- Nature reclaims everything. She's just faster about it.
- Fungus among us. Titan-sized.
- Her garden, her rules, her regrettable guests.
- Bring something sharp. It's gardening, just faster.

### `boss-sol`

> Sol Heredit, finale of the Fortis Colosseum.

- **when:** Sol Heredit appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Sol Heredit. It's footwork or a funeral, and his spear doesn't mind which.
- He punishes greed. Take the openings he gives you, not the ones you want.
- The shield bash rearranges the brave. Stay sharp on the swaps.
- The Colosseum's final word, and he enunciates.
- Varlamore's champion of champions. The crowd adores him from a safe distance.
- He fights fair. Relentlessly, exhaustingly fair.
- Other cities have a river in the middle. Fortis has HIM.
- The sun crest isn't for show. He earned that.
- Win, and the city remembers your name. He'll remember it first.
- Salute him. He notices. Then he tests you anyway.

### `boss-hueycoatl`

> The Hueycoatl in the Varlamore hills.

- **when:** The Hueycoatl appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Hueycoatl. Bring friends. It won't mind, and you'll feel better.
- The head strikes where the tail distracts. Watch both ends.
- Varlamore's feathered serpent, older than the city and angrier.
- The locals leave it offerings. It leaves them alone. Mostly.
- Some call it a god, some call it a hunt. Everyone calls it dangerous.
- It's all feathers and fury, and the size of a cathedral.
- It shed its patience ages ago. The scales too, occasionally.
- The hills shake when it stirs. The hills have learned to cope.
- It moves like a flood and it's about as easy to argue with.
- Majestic from a distance. We are not at a distance.

### `boss-amoxliatl`

> Amoxliatl beneath the Tlati Rainforest.

- **when:** Amoxliatl appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Amoxliatl. Mind the ice, the floor is half the fight.
- The frost pulses come in rhythm. Move on the beat.
- A guardian of ice under a rainforest. The world is strange.
- The child of something far bigger. Try not to meet the parent.
- Whatever they worshipped down here, it's kept on ice under all that green.
- The glacial kin serve it without question. Or warmth.
- Not every icicle in there is aimed. Assume they are anyway.
- It guards a door best left shut. So naturally, adventurers.
- Cold hands, colder heart, coldest floor.
- Bundle up. Then fight for your life. In that order.

### `boss-yama`

> Yama, lord of the pit.

- **when:** Yama appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Yama. The lord of the pit deals in contracts. Read yours twice.
- The fire waves have gaps. The gaps are the contract's fine print.
- His court attends the fight. Ignore the audience, mind the host.
- A demon lord who prefers paperwork to war. The paperwork is war.
- Souls are his currency. Arrive rich, leave richer. Or don't leave.
- The pit answers to him. The pit is thorough.
- Every bargain down here favours the house.
- He's polite, for a cataclysm.
- Hell has middle management. He's upper.
- Sign nothing. Swing everything.

### `boss-graardor`

> General Graardor, Bandos general.

- **when:** General Graardor appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Graardor. Pray Melee and watch his goons, they cover what he doesn't.
- His ranged smash hits the whole room. Melee prayer won't stop it.
- The bodyguards attack in their own rhythm. Learn it, save the food.
- Last of the Ourg, this one. Bandos worked the rest to death.
- He's guarded that door since the Third Age. Dedication.
- Somewhere under the armour is a war god's favourite pet.
- FOR THE GLORY OF BANDOS! ...Sorry. It's catching.
- Six thousand years of shouting and still not hoarse.
- Big, loud, and honest about both. Almost admirable.
- He hits like a falling building. Do keep the prayer up.

### `boss-kril`

> K'ril Tsutsaroth, Zamorak general.

- **when:** K'ril Tsutsaroth appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- K'ril. Pray Melee, but his roar breaks through and empties it. Keep a restore up.
- He doesn't fight fair. Nothing down here does.
- Mind the poison off those halberd swings.
- Zamorak's general. A greater demon with a title, and the title does a lot of work.
- His bodyguards take their jobs very seriously. Briefly.
- Two blades, one very loud demon.
- Demon logic: if it moves, halberd it.
- Zamorak promoted him for enthusiasm. Clearly not subtlety.
- ATTACK IN THE NAME OF ZAMORAK! ...He means it, too.
- The chaos god's finest. Chaos included at no extra charge.

### `boss-zilyana`

> Commander Zilyana, Saradomin general.

- **when:** Commander Zilyana appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Zilyana. Pray Magic, the lightning is the killer, not the sword.
- She is FAST. Don't stand there admiring the wings.
- An Icyene, one of the last left. Saradomin keeps her close.
- She fought in the God Wars and never really stopped.
- All grace and glowing swords until the lightning starts.
- A unicorn, a lion and a wolf guard her. Saradomin collects odd friends.
- The hilt she guards is worth more than this whole dungeon.
- Saradomin be praised, from behind a prayer, ideally.
- She means the shouting. Icyene always mean the shouting.
- Wings of light, sword of light, patience of a thunderstorm.

### `boss-kreearra`

> Kree'arra, Armadyl general.

- **when:** Kree'arra appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Kree'arra. Pray Ranged, and the wind will still shove you about.
- Blades can't reach him up there. Bring answers that fly.
- The gusts throw you across the room. Plant your feet and keep at it.
- An aviansie war leader. His kind nearly died out fighting over this place.
- Armadyl's general. Justice, with feathers on.
- The feathers drift down like snow. Sharp snow.
- His flock follows him into every fight. Loyal to the last feather.
- The skies belonged to the aviansie once. He remembers.
- Mind the draft. It doesn't care whose side you're on.
- All those wings and he still won't leave the room. Principles.

### `boss-nex`

> Nex, Zaros general in the Ancient Prison.

- **when:** Nex appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Nex. Smoke first, keep an antipoison handy.
- When she calls the shadows, step out of them. Quickly.
- When she starts her blood siphon, stop hitting her. Your hits heal her.
- When the ice comes, keep moving or she'll entomb you.
- Saradomin's people froze her down here. The door was meant to stay shut.
- Zaros's finest general. The other gods teamed up just to contain her.
- Four elements, then pure darkness. She saves the worst for last.
- The Ancient Prison held for millennia. We let her out for sport.
- She talks the whole fight. None of it's encouraging.
- Not an ounce of mercy in her. A fair bit of chill, though.

### `boss-zulrah`

> Zulrah, the serpent of Zul-Andra.

- **when:** Zulrah appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Zulrah. Green form, pray Ranged. Blue, pray Magic. Red, just move.
- The clouds are poison. Obviously. Don't stand in them.
- Snakelings underfoot, swat them or step away.
- It surfaces where it pleases. Learn its moods and be elsewhere.
- The serpent god of Zul-Andra. The villagers worship it AND fight it. Complicated.
- Sacrifices kept it calm for generations. You are not a sacrifice. Probably.
- Venom with a shrine of its own. Somebody's idea of worship.
- The swamp belongs to it. We're just visiting.
- It has three moods and all of them are hostile.
- Toxic doesn't begin to cover this relationship.

### `boss-brutus`

> Brutus in his pen, before the turn. Cache ids 15626-15627, combat 30.

- **when:** Brutus appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- That is a very large animal in a very small pen.
- Level thirty. I don't believe that for a second.
- He looks calm. Things in pens usually look calm.
- Whoever built that fence was an optimist.
- He's a big boy, and he's being polite about it.
- Keep your distance. Big animals don't need a reason.
- He's waiting for someone. I'd rather it not be me.
- He's holding something back. You can see it in the shoulders.
- A pen this size is a suggestion, not a wall.
- Say hello. Quietly.

### `boss-brutus-demonic`

> Demonic Brutus, the turned form. Cache ids 15628-15629, combat 1224.

- **when:** Demonic Brutus appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- There it is. That was the real one all along.
- The pen was never going to hold that.
- Twelve hundred and twenty four. The thirty was a costume.
- Well. He grew.
- That's not the same animal, and we both know it.
- It's stopped pretending. Get your prayer up.
- It let us walk right up to it first. That was on purpose.
- Nothing that size hits softly. Mind yourself.
- I take back everything I said about the fence.
- I liked him better in the pen.

### `boss-vorkath`

> Vorkath, the undead dragon of Ungael.

- **when:** Vorkath appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Vorkath. When the pink fireball goes up, MOVE. That one's most of your health.
- The ice pins you in place, then the spawn crawls out. Crumble it or eat the hit.
- Acid on the floor. Walk the gaps and don't stop moving.
- Antifire and prayer. He breathes most things a dragon can.
- A failed experiment that refused to stay failed.
- Zorgoth made him. The dragonkin's leftovers, still hungry.
- He was dead once. It didn't take.
- An island all to himself and he still won't share.
- The head on your wall would be the size of a boat.
- Sleepy-looking, isn't he. He wakes up fast.

### `boss-cerberus`

> Cerberus, hellhound of the underworld.

- **when:** Cerberus appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Cerberus. When the souls rise, match your prayer to each one's weapon.
- Mind the lava pools, she spits them where you stand.
- Three heads, three attack styles. Keep the prayers nimble.
- The guard dog of this corner of the underworld.
- Hellhounds answer to her. That should tell you something.
- She belongs to the Key Master, and his keys are soul-shaped.
- Good dog. Terrible circumstances.
- She howls and the ghosts come running. Rude of them.
- Under all that fire and hate, she's only doing her job.
- Fetch has a very different meaning down here.

### `boss-kraken`

> The Kraken, cave kraken boss.

- **when:** Kraken appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Kraken. Disturb the little pools first or the big one sulks below.
- Magic only, blades and arrows flop in the water.
- The tentacles keep you busy while the head does the real work.
- It's bigger under the water than over it. Most things are.
- Fishermen's tales got this one right, unfortunately.
- That whirlpool isn't there for decoration. Keep out of it.
- Deep water hides deep problems. This one has arms.
- It naps under the surface. You're the alarm.
- Calamari the size of a house, and it fights back.
- The sea keeps its monsters. This one it lends out.

### `boss-thermy`

> The Thermonuclear smoke devil.

- **when:** Thermonuclear smoke devil appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Face mask on? The smoke is the entire point of it.
- It fills the whole room. There's no clean air, so keep your prayers up.
- A smoke devil that ate far, far too well.
- The little ones sting. The big one suffocates.
- Born in a cavern of fumes and very committed to the theme.
- Breathe shallow, hit deep.
- It's less a creature, more a weather condition with teeth.
- The slayer masters mention it quietly, like a bad memory.
- All this smoke and no fire anywhere. That's not how smoke works.
- If your eyes are watering, you're doing it right.

### `boss-hydra`

> The Alchemical Hydra beneath Mount Karuulm.

- **when:** Alchemical Hydra appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Hydra. Lure it over the vents, it only drops its guard once it's doused.
- New head, new attack. Swap your prayer when it swaps its mood.
- It starts nasty and finishes worse. Keep up with it.
- The chemists under Karuulm MADE this. On purpose. With funding.
- Alchemy gave it heads. Nobody gave it manners.
- Mind the floor, it redecorates with hazards.
- Cut off one head and it grows an attitude.
- The Lovakengj call it research. The research bites.
- It's got more heads than sense, and it isn't short of heads.
- Green means poison. Blue means lightning. Red means regret.

### `boss-sire`

> The Abyssal Sire in the Abyssal Nexus.

- **when:** Abyssal Sire appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Sire. Disorient it while the vents hiss or the spawn swarm you.
- Silence the tentacles, then the real fight starts.
- It dreams in that sludge. You're the interruption.
- The abyss made demons. This one makes MORE demons.
- Every whip started life down here. Think on that.
- The Nexus hums when it stirs. The hum is a warning.
- It's an ecosystem with a temper.
- Knock first. It won't care, but I'll feel better.
- The spawn have their father's everything. Tragically.
- One day the abyss will explain itself. Not today.

### `boss-grotesque`

> The Grotesque Guardians, Dusk and Dawn, on the Slayer Tower roof.

- **when:** Dusk, Dawn appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- The Guardians. Dawn flies where a blade can't reach her. Bring a bow.
- When the ceiling starts falling, watch your feet, not the fight.
- Dawn heals if you let her feed. Don't let her feed.
- Dusk hits like the roof he lives on. Hard, and all at once.
- Gargoyles who got too big for the gutters.
- They dance this rooftop every night. You're cutting in.
- Stone by day, murder by night.
- Two of them, one of you. I don't really count...
- Most towers keep their trouble out of sight. This one put it on the roof.
- Mind the orbs, mind the rubble, mind the everything.

### `boss-skotizo`

> Skotizo beneath the Catacombs of Kourend.

- **when:** Skotizo appearing
- **does:** occasion (never held back)
- **pacing:** priority 90, cooldown 1h, both

- Skotizo. Douse the altars or it shrugs your hits off.
- The altars feed it. Starve them first.
- Demonbane weapons sing down here. Arclight especially.
- It set up under the Catacombs long ago, and nobody's managed to evict it.
- Kourend built a church district on top of THIS. Bold.
- The reanimated rise to help it. Terribly loyal, the dead.
- A demon so large the ceiling is a formality.
- The dark totem was the key. You did knock, technically.
- Its shadow arrives a moment before it does.
- Up in the Catacombs it's whispers. Down here, it's shouting.

### `boss-while-low`

> Combinator example: only complains if a boss is near AND you're hurt.

- **when:** HP under 50% AND Great Olm, Verzik Vitur, TzKal-Zuk, Nex, Vorkath, Zulrah within 12 tiles, in sight
- **does:** occasion (never held back)
- **pacing:** priority 130, cooldown 3m, both

- You are NOT winning this on {hp} HP.
- Heal up or leave, {player}.

### `kill-boss-celebrate`

> A proper celebration for a boss kill: cheer, dance, then a jump for joy (862/866/2109, 7.4s in total). The 6-8 tick delay lets the fight formally end and the spectating ward wind down first, so the celebration follows the fight instead of colliding with it - the ward's own release is 4 ticks, which this clears. It was 13-16, which cleared the ward three times over and left an eight second hole between the kill and the cheer. namedBoss: true keeps it for the bosses named in the boss rules: play test, 14 Oct, it had worn out on every level-100 Slayer demon of a task.

- **when:** a kill
- **does:** mood +18; marks this place: 'you put that thing down'; animation chain; holds still
- **pacing:** priority 70, cooldown 30s, delay 6-8 ticks, overhead

- YES! You did it!
- {npc} is down! I knew you had it!
- That was incredible. Absolutely incredible.
- You beat {npc}! Take a bow. I'll wait.
- I was never worried. Well. Barely worried.
- Look at you. Champion.
- I'm telling everyone about this.
- That was magnificent. {npc} never stood a chance.
- You were unbelievable just then.
- That'll be the best thing I see all week.

### `kill-count-first`

> The game's own count reaching one: the first kill of a boss on this account, whoever was watching. The delay lets the kill line land first.

- **when:** killcount
- **does:** mood +12
- **pacing:** priority 75, cooldown 30s, delay 3-5 ticks

- Your first {boss}. It goes in the book with the date.
- One {boss}. The first of many, I suspect.
- {boss}, once. I've been saving a good page for this.

### `kill-count-round`

> The counts a person says out loud - five, ten, twenty-five, fifty, seventy-five, the hundreds, then every five hundred. The account's number rather than the follower's tally, so it can say forty-first on the day they met. Acknowledging the cycle is what makes the fiftieth trip feel watched rather than repeated.

- **when:** killcount
- **does:** mood +8
- **pacing:** priority 72, cooldown 1m, delay 3-5 ticks

- {boss}, {count} times. I've stopped bringing the notes; I know how it goes.
- Your {ordinal} {boss}. That page is filling up.
- {ordinal} time. I could draw {boss} from memory now.
- That makes {count} {boss}. The ledger has a column just for it.
- {count}. Hard to believe there was ever a first {boss}.

### `kill-count-thousand`

> A thousand of one boss, and every thousand after. Outranks the round line.

- **when:** killcount
- **does:** mood +14; holds the floor 6s
- **pacing:** priority 76, cooldown 1m, delay 3-5 ticks

- {count} {boss}. I've bound a new volume for it.
- {count}. If {boss} keeps a book on you, it's a long one.
- Your {ordinal} {boss}. I ran out of room in the margin around the six hundredth.

## quest

*Famous quest figures nearby.*

### `quest-wise-old-man`

> The Wise Old Man of Draynor.

- **when:** Wise Old Man within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- The Wise Old Man of Draynor. Partly wise, entirely dangerous.
- He robbed a bank in that very outfit and everyone just... let him.
- Ask him about his cape. Or his exploits. He'll tell you either way.

### `quest-hans`

> Hans, circling Lumbridge Castle.

- **when:** Hans within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- That's Hans. He's walked that castle circuit longer than anyone's been alive.
- Hans knows exactly how long you've been at this. To the day. Ask him.
- Still on patrol after all these years. Dedication or habit, hard to say.

### `quest-duke-horacio`

> Duke Horacio of Lumbridge.

- **when:** Duke Horacio within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Duke Horacio himself. Lovely man. Bit preoccupied with dragons.
- The Duke's handed out anti-dragon shields for years and never once run out.
- Rules all of Lumbridge from one room. Efficient, really.

### `quest-cook`

> The Lumbridge cook. NOTE: other NPCs named Cook exist and will match too.

- **when:** Cook within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- A cook in distress, I'd wager. There's usually an egg involved.
- The kitchens of Gielinor would collapse without adventurers, you know.
- If he asks for flour, eggs and milk, act surprised. It's kinder.

### `quest-father-aereck`

> Father Aereck of the Lumbridge church.

- **when:** Father Aereck within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Father Aereck. Between the church and the graveyard, he's never short of work.
- He'll want help with the restless sort. The buried restless sort.
- A good shepherd, even when the flock includes ghosts.

### `quest-fred-the-farmer`

> Fred the Farmer of Lumbridge.

- **when:** Fred the Farmer within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Fred the Farmer. Do NOT mention the shears.
- He guards those sheep like they're made of gold. They're wool, mostly.
- Twenty balls of wool. That request outlives us all.

### `quest-doric`

> Doric the dwarf smith near Falador.

- **when:** Doric within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Doric the dwarf. Finest anvils this side of Falador, and he shares.
- He just wants his clay and ores. Salt of the earth. Literally.
- He'll grumble at you, then lend you an anvil. Dwarves are like that.

### `quest-thurgo`

> Thurgo, last of the Imcando dwarves.

- **when:** Thurgo within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Thurgo. The last of the Imcando smiths, and powered by redberry pie.
- Bring pie. I'm serious. It's the only currency he respects.
- The man reforged a knight's sword from a portrait. For pie.

### `quest-oziach`

> Oziach of Edgeville.

- **when:** Oziach within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Oziach. He decides who's champion enough for rune platebodies.
- Sends greenhorns at Elvarg and sells armour to the survivors. A system.
- Don't mind the mumbling. He's like that with everyone.

### `quest-guildmaster`

> The Champions' Guild Guildmaster.

- **when:** Guildmaster within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- The Guildmaster of the Champions' Guild. He starts legends for a living.
- The whole dragon business starts here. Ask him about Crandor.
- Thirty-two quest points just to hear him say hello. Worth it.

### `quest-gypsy-aris`

> Gypsy Aris of Varrock Square.

- **when:** Aris within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Gypsy Aris. She saw this conversation coming, of course.
- That crystal ball's started more adventures than any sword in Varrock.
- Cross her palm with coin and she'll show you the world ending. Cheery.

### `quest-sir-amik`

> Sir Amik Varze of the White Knights.

- **when:** Sir Amik Varze within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Sir Amik Varze, head of the White Knights. You can tell by all the stairs.
- The man runs Falador from a tower and still needs errand-runners.
- Knighthood looks a lot like paperwork from up close, doesn't it.

### `quest-sir-tiffy`

> Sir Tiffy Cashien in Falador Park.

- **when:** Sir Tiffy Cashien within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Sir Tiffy Cashien. Don't let the park bench fool you.
- The most dangerous man in Falador is feeding the ducks.
- Temple Knight recruitment happens over cucumber sandwiches. Naturally.

### `quest-king-roald`

> King Roald of Misthalin.

- **when:** King Roald within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- King Roald of Misthalin. Bow. Or nod. He's flexible.
- Half the kingdom's problems cross his desk. The other half cross ours.
- A king who takes walk-in appointments. Varrock is lucky.

### `quest-reldo`

> Reldo, the Varrock palace librarian.

- **when:** Reldo within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Reldo, the palace librarian. If it's written down, he's read it twice.
- Half of Varrock's history lives in his head. The dusty half.
- Ask him anything. The answer involves a book. It always involves a book.

### `quest-aubury`

> Aubury of the Varrock rune shop.

- **when:** Aubury within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Aubury. Sells runes, and knows a good deal more about them than he lets on.
- There's more to that little shop than runes. Ask him about essence sometime.
- The most powerful shopkeeper in Varrock, and it isn't close.

### `quest-sedridor`

> Sedridor, head of the Wizards Tower.

- **when:** *Sedridor within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Sedridor, head wizard of the tower. He works from the basement, under all the others.
- The tower burned down once, you know. Wizards don't take a hint.
- He studies the runes that move the world. And answers letters. Busy man.

### `quest-traiborn`

> Wizard Traiborn of the Wizards Tower.

- **when:** Wizard Traiborn within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Traiborn. Brilliant wizard, hasn't a clue where anything is. Lovely company.
- He'll ask for bones and forget why. Bring bones anyway.
- Thingummywut! ...I don't know either. Nobody knows.

### `quest-gertrude`

> Gertrude of Varrock.

- **when:** Gertrude within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Gertrude. Cat breeder, mother of many, patience of a saint.
- Every adventurer's first cat came through her door.
- Fluffs has run off again, I'd bet my hood on it.

### `quest-romeo-juliet`

> Romeo and Juliet of Varrock.

- **when:** Romeo, Juliet within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Ah, the star-crossed pair. It ends badly whichever way you help.
- Romeo forgets, Juliet plans, Varrock gossips.
- Some romances need an adventurer. This one needed a warning.

### `quest-oddenstein`

> Professor Oddenstein in Draynor Manor.

- **when:** Professor Oddenstein within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Professor Oddenstein. Turns people into chickens. Turns them back, mostly.
- The manor attic hums when he's working. Knock first.
- Science, he calls it. The chicken had opinions.

### `quest-veronica`

> Veronica outside Draynor Manor.

- **when:** Veronica within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Veronica's been waiting by that gate a long while. Her fiance went inside.
- Draynor Manor takes guests easily. It's the leaving it struggles with.
- Don't promise her anything unless you're actually going in there.

### `quest-ned`

> Ned of Draynor Village.

- **when:** Ned within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Ned of Draynor. Ropes, wigs, and questionable sailing credentials.
- Give Ned some wool and he'll make you a rope. Or a wig, if you're desperate.
- Captain Ned, if you're feeling generous about the word captain.

### `quest-aggie`

> Aggie the Draynor witch.

- **when:** Aggie within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Aggie the witch. Dyes, potions, and the odd miracle in a pot.
- Need something coloured or conjured? She's your woman.
- The friendliest witch in Misthalin. It's not a crowded field, mind.

### `quest-hetty`

> Hetty the Rimmington witch.

- **when:** Hetty within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Hetty of Rimmington. Her potions improve your magic and your humility.
- She asked someone for a rat's tail once, and they actually brought her one.
- Cauldron's always on. Best not to ask what's in it.

### `quest-morgan`

> Morgan of Draynor Village.

- **when:** Morgan within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Morgan of Draynor. He keeps garlic on hand for very practical reasons.
- His village had a vampyre problem. Had. Adventurers, eh?
- The nervous look is earned. Count Draynor lived just up the road.

### `quest-redbeard-frank`

> Redbeard Frank of Port Sarim.

- **when:** Redbeard Frank within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Redbeard Frank. He'd trade his secrets for Karamja rum. Has done.
- Every pirate in Port Sarim has a story. Frank's ends with a spade.
- He calls himself a retired pirate. The retired part I'd want proof of.

### `quest-luthas`

> Luthas of the Musa Point plantation.

- **when:** Luthas within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Luthas runs the banana plantation. Don't ask about the crates.
- Honest work, banana picking. You'll smell of fruit for a week.
- Ten bananas to a crate. He'll want it full, and he'll check.

### `quest-zanik`

> Zanik of the Dorgeshuun.

- **when:** Zanik within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Zanik of the Dorgeshuun. The bravest goblin you'll ever meet.
- Her people hid underground for ages. She walked out first.
- Destiny follows that one around. Try to keep up.

### `quest-juna`

> Juna, guardian of the Tears of Guthix.

- **when:** Juna within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Juna, the serpent of the chasm. She guards Guthix's own tears.
- Tell her a good story. She's heard thousands and still listens.
- A guardian of Guthix, patient as stone. Mind your manners.

### `quest-hazelmere`

> Hazelmere, the ancient gnome mystic.

- **when:** Hazelmere within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Hazelmere. One of the oldest, wisest gnomes alive, and he talks in signs.
- He lives alone with his thoughts, and his thoughts are worth the trip.
- The Grand Tree trusts him. That should settle any doubts.

### `quest-narnode`

> King Narnode Shareen of the Grand Tree.

- **when:** King Narnode Shareen within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- King Narnode of the Grand Tree. Small king, enormous responsibilities.
- He rules a tree. A magnificent tree, but still. A tree.
- The gnomes' best diplomat, and their most worried one.

### `quest-glough`

> Glough of the Tree Gnome Stronghold.

- **when:** Glough within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Glough. Watch that one. The trees whisper complaints about him.
- Ambitious, for a gnome. That never ends quietly.
- If he offers you a job, read the fine print. Twice.

### `quest-sanfew`

> Sanfew of Taverley.

- **when:** Sanfew within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Sanfew of Taverley. The druids trust him with their hardest cures.
- He's part herblore, part stubbornness, all druid.
- If it can be brewed, he's brewed it. If it can't, give him a week.

### `quest-kaqemeex`

> Kaqemeex of the Taverley stone circle.

- **when:** Kaqemeex within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Kaqemeex. He'll teach herblore to anyone willing to put the work in.
- Keeper of the stone circle. The Zamorakians ruined the last one. Sore subject.
- A druid of the old ways, and generous with them.

### `quest-elena`

> Elena of East Ardougne.

- **when:** Elena within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Elena of Ardougne. She crossed the wall for the truth and paid for it.
- Plague researcher, prisoner, hero. In that order.
- The bravest person in either half of this city.

### `quest-king-lathas`

> King Lathas of East Ardougne.

- **when:** King Lathas within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- King Lathas. Smile, nod, and count your change afterwards.
- The wall was his idea. The reasons shift depending on who's asking.
- Some kings earn statues. Some earn questions.

### `quest-sir-prysin`

> Sir Prysin of Varrock Palace.

- **when:** Sir Prysin within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Sir Prysin. Ask him about Silverlight. Then ask where the keys went.
- A knight who lost the keys to a demon-slaying sword. Varrock's finest.
- Delrith nearly rose again because of his filing system.

### `quest-evil-dave`

> Evil Dave of Edgeville. Well, of his mum's basement.

- **when:** Evil Dave within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Evil Dave. His lair of DARKNESS is his mum's cellar.
- He's mastered evil stew and very little else. Bless him.
- The hellcats respect him. The hellcats are the only ones.

### `quest-ali-morrisane`

> Ali Morrisane of Al Kharid.

- **when:** Ali Morrisane within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Ali Morrisane, the world's greatest salesman. His words. Always his words.
- He could sell sand in the Kharidian desert. He HAS.
- Fine man. Check your pockets after. Fine man.

### `quest-oracle`

> The Oracle atop Ice Mountain.

- **when:** Oracle within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- The Oracle of Ice Mountain. Speaks in riddles, means every word.
- People climb all this way for wisdom and come down confused. Same thing, really.
- Ask her about the dragon and she'll answer in verse. Bring paper.

### `quest-ernest`

> Ernest, formerly a chicken, of Draynor Manor.

- **when:** Ernest within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Ernest. He went into the manor a man and spent a while as a chicken.
- The manor did that to him. The manor does that to people.
- Fully recovered. Mostly. No more feathers, anyway.

### `quest-alina-noah`

> Alina and Noah, waiting for the delegation in Varrock Square.

- **when:** Alina, Noah within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Alina and Noah. One's excited, and you can guess which.
- She's heard the news before Varrock has. He'd rather not hear it at all.
- Alina's always got news. Noah's always got somewhere else to be.

### `quest-sergeant-tobyn`

> Sergeant Tobyn, keeping order in Varrock Square, and later on the palace roof.

- **when:** Sergeant Tobyn within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Sergeant Tobyn keeps order round here, when order lets him.
- A sergeant with a very straight face. Varrock tests it daily.
- Tobyn's seen it all, he'd tell you. I'd leave him thinking so.

### `quest-itzla`

> Prince Itzla Arkan, heir to the throne of Varlamore. NOTE: his versions in later quests share the name and will match too.

- **when:** Prince Itzla Arkan within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Prince Itzla Arkan, who'll give you the full title if you've got a minute.
- Actual royalty, this close. I've straightened my collar.
- Heir to the Sun Throne, and never far from a commotion.

### `quest-regulus-cento`

> Regulus Cento and his quetzal, outside Varrock's east gate and in Civitas illa Fortis.

- **when:** Regulus Cento within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Regulus Cento keeps the bird that goes between Varrock and Varlamore.
- The bird does the flying and he does the talking. A fair split.
- He dresses well. The bird dresses better.

### `quest-boy`

> The boy by the witch's hedge in north Taverley, who starts Witch's House. He is the only NPC named Boy.

- **when:** Boy within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Every village has a boy who kicks things over hedges. Taverley has this one.
- He stands by that hedge as if it owes him something.
- People call him sad-looking. I'd have said patient.

### `quest-nora-t-hagg`

> Nora T. Hagg, the witch, walking her hedged garden in north Taverley.

- **when:** Nora T. Hagg within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Nora T. Hagg. Say it quickly and you'll hear why I don't.
- She walks that lawn like a sentry nobody ever relieves.
- She has a long garden and, I'd guess, a short temper.

### `quest-veos`

> Veos, the Kourend treasure hunter, at the Port Sarim and Port Piscarilius docks. NOTE: his copies in quests and cutscenes share the name and will match too.

- **when:** Veos within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Kourend's his home, and Veos does like to mention it.
- A treasure hunter with his own ship. Most of them make do with a spade.

### `quest-leenz`

> Leenz, who keeps the general store in Port Piscarilius.

- **when:** Leenz within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Leenz keeps the general store, and greets every customer in rhyme.
- Leenz takes her rhymes seriously. I'd advise against marking her work.

### `quest-regath`

> Regath, who keeps Regath's Wares in Arceuus.

- **when:** Regath within 5 tiles, in sight
- **pacing:** priority 35, cooldown 1h, delay 2 ticks, overhead

- Regath deals in many things, he says. Mostly runes, from what I've seen.
- Regath talks as if every sentence is going in a book. Some of them will.

### `quest-complete`

> The quest reward scroll - the one moment the game hands the player literal paperwork, witnessed by a professional. Tally 'quests' counts them.

- **when:** questcomplete
- **does:** mood +8; occasion (never held back)
- **pacing:** priority 85, cooldown 1m, delay 3 ticks

- {quest}, done and witnessed. That deserves a full page.
- That's {quest} closed. Their scroll is nice. Mine has footnotes.
- {quest}. Signed, sealed, filed.
- Finished. Let me see the scroll before you put it away.

### `quest-milestone-10`

> The tenth quest completed since the follower arrived. Exact bracket, once.

- **when:** questcomplete AND quests count at 10+
- **pacing:** priority 60, cooldown 1m, delay 5 ticks

- Ten quests since we met. The index has its own page now.

## thrall

*When it stands in for a thrall.*

### `thrall-start`

> Fires when the follower takes a thrall's place. {style} is melee, ranged or magic.

- **when:** thrall duty starting
- **pacing:** priority 95, cooldown 5s, overhead

- A {style} thrall, am I? I'll allow it. Briefly.
- Summoned! I make a better thrall than any pile of bones.
- Fine, fine. Point me at something. I'm on {style} duty.
- From companion to conscript. The things I do for you.
- Put to work. The pay had better improve.
- A thrall with a notebook. First of my kind.

### `thrall-switch`

> Fires when a new thrall is summoned while the follower is already one. {style} is the new type, {from} the one it just left.

- **when:** thrallswitch
- **pacing:** priority 95, cooldown 5s, overhead

- Done with {from}, are we? {style} it is.
- Bit of a reshuffle. {from} out, {style} in.
- Changed your mind again. Fine, {style}.
- One moment I'm {from}, the next I'm {style}. Keeps me on my toes.
- New orders. I serve at the pleasure of a whim.
- {from} to {style}, just like that. Versatile, me.

### `thrall-end`

> Fires when the thrall's time runs out and the follower returns to your side.

- **when:** thrall duty ending
- **pacing:** priority 95, cooldown 5s, overhead

- Time's up. Back to being your better half.
- Spell's worn off and I'm still here. Convenient, that.
- That's thrall duty done. I'll be expecting a raise.
- And... I'm me again. That was strangely fun.
- Discharged with honours. I assume.
- Freedom. I'll put the whole episode in an appendix.

## mimic

*Copying your emotes.*

### `mimic-emotes`

> Copies your emote. Every animation in RuneLite's constants with EMOTE in its name that is a ONE-SHOT, measured from the cache. Matching on the EMOTE_ prefix alone missed HUMAN_EMOTE_CRABDANCE and others, which is why the crab dance never mirrored. The authored loops are in mimic-emote-loops instead: a chain ends when the animation controller finishes, which a looping clip never does.

- **when:** your animation 855, 856, 857, 858, 859, 860, 861, 862, 863, 864, 865, 866, 868, 870, 872, 874, 1128, 1129, 1130, 1131, 1374, 1481, 1482, 1483, 1484, 1485, 1708, 2105, 2106, 2107, 2108, 2109, 2110, 2111, 2112, 2113, 2709, 2836, 3544, 3859, 4275, 4276, 4278, 4280, 4751, 5059, 5312, 5313, 5315, 5316, 5693, 5694, 5695, 5696, 5697, 5698, 5699, 5700, 6111, 7131, 7278, 7751, 8331, 8332, 8536, 8537, 8541, 8792, 8855, 8917, 9208, 9831, 9832, 9833, 9834, 9835, 10031, 10038, 10051, 10053, 10503, 10678, 10781, 10796, 11526, 12514, 13801, 13803
- **does:** mirrors you
- **pacing:** priority 40, cooldown 2s, delay 1-4 ticks

- *(no lines - animation or effect only)*

### `answer-yes`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a yes back. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 855 AND addressed
- **does:** animation 855
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-no`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a no gets a think. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 856 AND addressed
- **does:** animation 857
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-think`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a think gets a shrug. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 857 AND addressed
- **does:** animation 2113
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-bow`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a bow gets a bow. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 858 AND addressed
- **does:** animation 858
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-angry`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: anger gets a shrug. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 859 AND addressed
- **does:** animation 2113
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-cry`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: crying gets a think. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 860 AND addressed
- **does:** animation 857
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-laugh`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a laugh is caught. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 861 AND addressed
- **does:** animation 861
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-cheer`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a cheer gets a clap. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 862 AND addressed
- **does:** animation 865
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-wave`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a wave gets a wave. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 863 AND addressed
- **does:** animation 863
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-beckon`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a beckon gets a yes. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 864 AND addressed
- **does:** animation 855
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-clap`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: applause gets a bow. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 865 AND addressed
- **does:** animation 858
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-dance`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a dance gets a dance, a beat behind. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 866 AND addressed
- **does:** animation 866
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-jump-for-joy`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a jump for joy gets a cheer. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2109 AND addressed
- **does:** animation 862
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-salute`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a salute is returned. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2112 AND addressed
- **does:** animation 2112
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-shrug`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a shrug gets a shrug. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2113 AND addressed
- **does:** animation 2113
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-yawn`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a yawn is catching. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2111 AND addressed
- **does:** animation 2111
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-blow-kiss`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a blown kiss gets a bow. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 1374 AND addressed
- **does:** animation 858
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-raspberry`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a raspberry gets anger. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2110 AND addressed
- **does:** animation 859
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-panic`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: panic gets a think. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2105 AND addressed
- **does:** animation 857
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-jig`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a jig gets a jig. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2106 AND addressed
- **does:** animation 2106
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-spin`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a spin gets a spin. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2107 AND addressed
- **does:** animation 2107
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `answer-headbang`

> An emote done AT the follower - within a tile and facing it - is answered rather than copied: a headbang gets a headbang. Outranks the mimic by priority for that one event, so the copy never plays on top. The closest OSRS gets to petting the dog: an interaction pointed at it, replied to.

- **when:** your animation 2108 AND addressed
- **does:** animation 2108
- **pacing:** priority 45, cooldown 3s, delay 2-3 ticks

- *(no lines - animation or effect only)*

### `mimic-eat`

> Eats when you eat. 829 covers most food, 1191 bananas, 1194 rum, 1327 tankards; add more ids via ::follower watch.

- **when:** your animation 829, 1191, 1194, 1327
- **does:** mirrors you
- **pacing:** priority 40, cooldown 8s

- *(no lines - animation or effect only)*

### `mimic-emote-loops`

> Holds your emote for as long as you do. The game splits a held emote in two - an entry clip, which mimic-emotes copies, and a loop it plays until you stop. The loop is mirrored as a POSE rather than an emote, because a pose loops by nature and is released when your animation changes; playing it as an emote would wait forever for a finish that never comes.

- **when:** your animation 189, 190, 191, 192, 193, 194, 195, 196, 197, 2691, 3193, 3803, 7279, 10048, 10049, 10050, 10052, 10061, 10062, 10797, 12050, 12051, 12052, 12053, 12054, 12055, 12056, 12057, 12058, 12059, 12060, 12061, 12062, 12063, 12064, 12065, 12066, 12067, 12068, 12070, 12071, 12072, 12073, 12075, 12076, 12077, 12078, 12079, 12080, 12085
- **does:** mirrors you
- **pacing:** priority 40, cooldown 0s, delay 1-3 ticks

- *(no lines - animation or effect only)*

### `mime-loops`

> Held as a POSE, because these all loop: mining 624-629/642/3873/4482 (frameStep 8), woodcutting 867-879/2117/2846/5383 (frameStep 6), fishing 618-623 (frameStep 42/44/2) and fletching 1248 (frameStep 15), all measured. The pose is released when your animation changes, which is what joining in until you stop actually means. atTheWork has it walk over first and copy from a tile the game would let you work from, facing the middle of the tree or rock, and stop the moment it is moved off it.

- **when:** repeating one action 30+ ticks AND a 35% roll
- **does:** mirrors you; holds still; only from beside the work
- **pacing:** priority 18, cooldown 3m

- *(no lines - animation or effect only)*

### `mime-oneshots`

> The same idea for the animations the cache says are ONE-SHOTS (frameStep -1): cooking 896/897, smithing 898/6712, crafting 1249. The server restarts these every cycle, so the follower having a single go and stopping is right; held as a pose they would loop wrong. atTheWork makes the go from beside the range or anvil, facing it, and then back to its seat. 4969 and 4975 used to be listed here as herblore and firemaking; they are the skill-cape emotes, not work.

- **when:** repeating one action 30+ ticks AND a 35% roll
- **does:** mirrors you; holds still; only from beside the work
- **pacing:** priority 18, cooldown 3m

- *(no lines - animation or effect only)*

### `mime-caught`

> Said occasionally instead of silently copying you, so the joining in reads as deliberate rather than as a rendering bug. Priority above the two mimes so it takes the tick when it wins.

- **when:** repeating one action 60+ ticks AND a 4% roll
- **pacing:** priority 19, cooldown 15m, overhead

- I've been copying you a while now. You've not noticed.
- I'm joining in. You carry on.
- It looked easy when you were doing it.
- I could do this. Given the tools. And the levels.
- Yes, I'm copying you. No, I won't be stopping.
- In my defence, it looked fun. It was fun.
- Purely observational. Ignore the arms.
- My hands are empty, but the technique's all there.
- Don't stare. I lose my place.
- Is this how you hold it? I've been guessing.
- I'll stop when you stop. It seemed only fair.
- Not copying. Corroborating.
- One of us is producing something. It isn't me.
- I'd log the hours, but there's no column for it.
- The notes can wait. This is research.
- You keep the pace. I'll keep the form.

### `bury-along`

> Buries along with you. 827 is HUMAN_PICKUPFLOOR, the bend the bury uses, proven on the follower by the bootlace and cat errands. A one-shot, so mirrored as an emote; the chance keeps it from copying every bone in the pile.

- **when:** your animation 827 AND a 30% roll
- **does:** mirrors you
- **pacing:** priority 18, cooldown 3s, delay 1-3 ticks

- *(no lines - animation or effect only)*

### `casting-along`

> Casts along with you, rarely. The utility spells only: low and high alchemy 712/713, the three enchants 719-721, superheat 725 and charge orb 726, all HUMAN_CAST one-shots from the cache. The graphic copies through the mirror's twelve-tick window. Five percent with a minute between, so it reads as joining in once, not as a second caster.

- **when:** your animation 712, 713, 719, 720, 721, 725, 726 AND a 5% roll
- **does:** mirrors you
- **pacing:** priority 18, cooldown 1m

- *(no lines - animation or effect only)*

## dialogs

*The conversations the follower starts. While one is open, Talk-to opens it instead of the everyday script; the branch marked as the answer is what replies.*

### `want-outing`

> Opened by the ask-outing rule. While the follower is waiting for an answer this REPLACES the everyday Talk-to script, so walking over and talking to it is how you answer - no magic word typed into public chat, and no follower listening to the whole street. The node carrying "answer" is what feeds the reply back to the rules: yes puts a want on the board, no closes it politely.

- **start**
  - Follower: Oh good, you came over.
  - Follower: I wanted to ask you something, and I'd rather ask it properly.
- **ask**
  - Follower: Have you got time to go somewhere with me? Nowhere far. Just somewhere I like.
- **why-q**
  - You: Why? What's brought this on?
- **why-a**
  - Follower: We go where you need to go. That's the arrangement, and no complaints from me.
  - Follower: But there are places I'd go if it were up to me, and it never is.
- **why-b**
  - Follower: So I'm asking. That's all.
- **yes-q**
  - You: All right. Where did you have in mind?
- **yes-a (answers 'yes')**
  - Follower: I've got a few in mind. Give me a moment to pick one.
- **no-q**
  - You: Not right now.
- **no-a (answers 'no')**
  - Follower: That's all right. It'll keep.
  - Follower: Lead on.

### `game-hands`

> A game, opened by the offer-game rule the same way want-outing is opened by ask-outing. The reveal is deliberately NOT in here: a tree is fixed text, so a guess it resolved itself would have the same answer every time. The box closes on "hold on", and the verdict arrives overhead a beat later from game-hands-won and game-hands-lost, which split a coin flip between them by priority. That also puts the reveal where the follower is standing rather than in a menu, which is where a reveal belongs.

- **start**
  - Follower: Right, stand there. This won't take long.
  - Follower: Both hands behind my back. One of them's got something in it.
- **ask**
  - Follower: Go on then. Which one?
- **what-q**
  - You: What have you got in there?
- **what-a**
  - Follower: That'd be telling.
- **left-q**
  - You: Left.
- **left-a (answers 'left')**
  - Follower: Left. You sound sure. Hold on.
- **right-q**
  - You: Right.
- **right-a (answers 'right')**
  - Follower: Right, is it. Hold on.
- **no-q**
  - You: Not now.
- **no-a (answers 'nogame')**
  - Follower: Pity. I was sure you'd go left.

### `nickname-offer`

> Opened by the ask-nickname rule, whose overhead line said the name. A tree is fixed text, so the name itself lives in that line and in the answer lines outside; in here the follower asks whether the player minds. yes makes it the nickname, no strikes it through for good, later leaves it in pencil.

- **start**
  - Follower: Ah, good. I wanted a word about the name.
- **ask**
  - Follower: I've been using one for you in the notes. You'll have heard it just now. Do you mind?
- **yes-q**
  - You: No, I like it.
- **yes-a (answers 'yes')**
  - Follower: Then it stays. I'll write it properly this time.
- **what-q**
  - You: Which name?
- **what-a**
  - Follower: The one I said just now. Tell me to ask later and I'll say it again.
- **no-q**
  - You: I'd rather you didn't.
- **no-a (answers 'no')**
  - Follower: Understood. That one's gone, and I won't bring it up again.
- **later-q**
  - You: Ask me later.
- **later-a (answers 'later')**
  - Follower: No rush. I'll leave it where it is for now.

## talk-to

The everyday Talk-to script is built in code (`FollowerPlugin.talkScript`) because it branches on live state - the mood band, the open wish, whether the wished item is in the bag, today's summary. Its text is reviewed in `TalkScriptTest`, which walks every structural variant. Read it there; it is deliberately not duplicated here.

