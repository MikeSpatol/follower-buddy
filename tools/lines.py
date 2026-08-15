"""
The line book: every line the follower can say, organised for review.

Generated straight from default-phrases.json and default-dialogs.json, so
it can never drift from what ships. Re-run after any corpus change:

    python tools/lines.py            # writes docs/lines.md
    python tools/lines.py --check    # exits 1 if docs/lines.md is stale

Each rule appears once, under its group, with the plain-English reading of
what fires it, what it does beyond speaking (mood, wish, prop, question...),
its pacing, and every line. The point is a single place to read the whole
voice against docs/voice.md.
"""
import io
import json
import os
import sys
from collections import OrderedDict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PHRASES = os.path.join(ROOT, "src/main/resources/com/follower/default-phrases.json")
DIALOGS = os.path.join(ROOT, "src/main/resources/com/follower/default-dialogs.json")
OUT = os.path.join(ROOT, "docs/lines.md")

# Group order and what each group is for, so the book reads top to bottom.
GROUPS = OrderedDict([
    ("misc", "Greetings, arrivals, level-ups, firsts, the everyday spine"),
    ("idle", "Standing still: chatter, wondering, offers, the trail-off's subjects"),
    ("reactions", "Answering the world: pets, drops, examines, bags, crowds"),
    ("combat", "The fight from the sidelines, and its ends"),
    ("health", "Hitpoints, prayer, poison, the warnings that must land"),
    ("memory", "Deaths, incidents, comforts, the things it keeps bringing up"),
    ("area", "Places: named lines, tastes, earned verdicts, the defences"),
    ("errand", "Its little trips: bank, altar, fire, cat, studies, explores"),
    ("souvenir", "Wishes, gifts, the pocket, and what it carries"),
    ("bet", "Its predictions about drops"),
    ("clock", "The hour and how long we have been at it"),
    ("gear", "What you put on"),
    ("boss", "Bosses sighted and fought"),
    ("quest", "Famous quest figures nearby"),
    ("thrall", "When it stands in for a thrall"),
    ("mimic", "Copying your emotes"),
])

TYPE_WORDS = {
    "always": "always",
    "chance": "a {percent}% roll",
    "idle": "standing still {ticks}+ ticks",
    "idlebelow": "standing still under {ticks} ticks",
    "login": "on login",
    "playerdeath": "on your death",
    "levelup": "on a level-up ({names})",
    "combat": "in a fight",
    "combatstart": "a fight starting",
    "combatend": "a fight ending",
    "npcspawn": "{names} appearing",
    "npcdespawn": "{names} leaving",
    "npcnearby": "{names} within {within} tiles{vis}",
    "npckill": "a kill",
    "loot": "loot",
    "lootworth": "loot worth {minimum}+",
    "damagetaken": "taking {minimum}+ damage",
    "healthbelow": "HP under {percent}%",
    "healthabove": "HP over {percent}%",
    "prayerbelow": "prayer under {percent}%",
    "prayerabove": "prayer over {percent}%",
    "energybelow": "run energy under {percent}%",
    "poisoned": "poisoned",
    "venomed": "venomed",
    "skulled": "skulled",
    "itemequipped": "equipping {ids}",
    "varbitequals": "varbit {varbit} = {value}",
    "inregion": "in region {regions}",
    "inarea": "in the area",
    "regionenter": "entering region {regions}",
    "returnvisit": "returning somewhere",
    "regionchange": "changing region",
    "petnearby": "a pet nearby",
    "chatmessage": "a chat message ({contains})",
    "examined": "being examined",
    "hovered": "being hovered {ticks}+ ticks",
    "answered": "the player answering '{is}'",
    "mood": "mood is {is}",
    "repeating": "repeating one action {ticks}+ ticks",
    "awayfor": "away {minimum}+ minutes",
    "tally": "{of} count at {minimum}+",
    "personalbest": "a personal best ({names})",
    "sessioncount": "session {minimum}-{maximum} (every {every})",
    "inventoryfree": "{maximum} or fewer free slots",
    "playersnearby": "{minimum}+ players within {within}",
    "wanting": "wanting somewhere",
    "wantfulfilled": "the want fulfilled",
    "wantexpired": "the want expired",
    "wishing": "wishing for {is}",
    "wishitemheld": "the wished item in the bag",
    "feelsabout": "feels {is} about here",
    "rolledfeeling": "rolled {is} about here",
    "remembers": "an incident remembered",
    "carrying": "carrying a souvenir",
    "souvenirlost": "the souvenir lost",
    "betting": "a bet open",
    "betwon": "the bet won",
    "betlost": "the bet lost",
    "timeofday": "time of day {is}",
    "sessionminutes": "session minute {minimum}-{maximum}",
    "asking": "a question open",
    "placescore": "place score {minimum}..{maximum}",
    "happenedhere": "something happened here",
    "heeded": "advice taken",
    "ignored": "advice ignored",
    "advising": "advice outstanding",
    "daysknown": "known {minimum}-{maximum} days",
    "anniversary": "the anniversary",
    "nicknamed": "nickname earned",
    "outgrew": "gear outgrown ({minimum}x)",
    "challenging": "a challenge running",
    "challengemet": "the challenge met",
    "challengefailed": "the challenge failed",
    "underfoot": "underfoot",
    "unattended": "unattended {ticks}+ ticks",
    "thievingstart": "thieving starting",
    "thievingend": "thieving ending",
    "thieving": "thieving",
    "bossfight": "a boss fight",
    "neardeathspot": "near where you died",
    "errandstart": "errand start: {names}",
    "errandend": "errand end: {names}",
    "animationself": "your animation {ids}",
    "boundary": "just after something ended{is_kind}",
    "staying": "on a player-commanded Stay",
    "inwilderness": "in the Wilderness",
    "thrallstart": "thrall duty starting",
    "thrallend": "thrall duty ending",
}


DEFAULTS = {"ticks": "100", "percent": "100", "within": "10", "minimum": "1"}


def fmt(template, c):
    def get(key, default="?"):
        v = c.get(key)
        if v is None:
            # A rule that leaves the selector off matches by regex or
            # by anything, so say so instead of printing the gap.
            if key == "names":
                if c.get("regex"):
                    return "matching /%s/" % c["regex"]
                if c.get("contains"):
                    return "containing '%s'" % c["contains"]
                return "any"
            if key == "contains" and c.get("regex"):
                return "matching /%s/" % c["regex"]
            return DEFAULTS.get(key, default)
        if isinstance(v, list):
            return ", ".join(str(x) for x in v)
        return str(v)
    out = template
    for key in ("percent", "ticks", "names", "within", "minimum", "maximum",
                "ids", "varbit", "value", "regions", "contains", "is", "of",
                "every"):
        out = out.replace("{" + key + "}", get(key))
    out = out.replace("{vis}", ", in sight" if c.get("visible") else "")
    out = out.replace("{is_kind}", " (%s)" % c["is"] if c.get("is") else "")
    # Open-ended bounds and unset selectors read as words, not question marks.
    out = out.replace("?..?", "any").replace("?-?", "any").replace(" (every ?)", "")
    out = out.replace("?..", "up to ").replace("..?", " or more").replace("?-", "up to ").replace("-?", "+")
    out = out.replace(" for ?", " for anything").replace(" is ?", " is anything")
    out = out.replace(" ?", " (any)")
    return out


def describe(c):
    if not isinstance(c, dict):
        return "?"
    t = (c.get("type") or "").lower()
    kids = c.get("conditions") or []
    if t == "all":
        return " AND ".join(describe(k) for k in kids)
    if t == "any":
        return "(" + " OR ".join(describe(k) for k in kids) + ")"
    if t == "none":
        return "NOT (" + " OR ".join(describe(k) for k in kids) + ")"
    return fmt(TYPE_WORDS.get(t, t), c)


def effects(r):
    out = []
    if r.get("mood"):
        out.append("mood %+d" % r["mood"])
    if r.get("occasion"):
        out.append("occasion (never held back)")
    if r.get("once"):
        out.append("once ever")
    if r.get("asks"):
        out.append("opens question `%s`" % r["asks"])
    if r.get("want"):
        out.append("wants %s (%s min)" % (r["want"].get("label"), r["want"].get("minutes")))
    if r.get("wish"):
        out.append("wishes for %s (%s min)" % (r["wish"].get("what"), r["wish"].get("minutes")))
    if r.get("grantsWish"):
        out.append("spends the wish")
    if r.get("pickUp"):
        p = r["pickUp"]
        out.append("%s '%s' (%s min)" % ("trades for" if p.get("swap") else "picks up",
                                          p.get("what"), p.get("minutes")))
    if r.get("bet"):
        out.append("bets on a %s drop" % ("rich" if r["bet"].get("rich") else "poor"))
    if r.get("challenge"):
        out.append("challenge: %s" % r["challenge"].get("about"))
    if r.get("advise"):
        out.append("advises: %s" % r["advise"].get("about"))
    if r.get("remember"):
        out.append("files incident `%s` as '%s'" % (r["remember"].get("key"), r["remember"].get("as")))
    if r.get("markHere"):
        out.append("marks this place: '%s'" % r["markHere"])
    if r.get("prop"):
        out.append("prop item %s, pose %s" % (r["prop"].get("item"), r["prop"].get("pose")))
    if r.get("animation"):
        out.append("animation %s" % r["animation"])
    if r.get("animations"):
        out.append("animation chain")
    if r.get("mirrorAnimation") or r.get("mirrorPose"):
        out.append("mirrors you")
    if r.get("hushMs"):
        out.append("holds the floor %ds" % (r["hushMs"] // 1000))
    if r.get("holdStill"):
        out.append("holds still")
    return out


def pacing(r):
    parts = ["priority %s" % r.get("priority", 50)]
    cd = r.get("cooldownMs", 10000)
    if cd >= 3600000:
        parts.append("cooldown %.0fh" % (cd / 3600000))
    elif cd >= 60000:
        parts.append("cooldown %dm" % (cd // 60000))
    else:
        parts.append("cooldown %ds" % (cd // 1000))
    if r.get("delayTicks"):
        d = "delay %s" % r["delayTicks"]
        if r.get("delayTicksMax"):
            d += "-%s" % r["delayTicksMax"]
        parts.append(d + " ticks")
    if r.get("output"):
        parts.append(r["output"])
    return ", ".join(parts)


def build():
    d = json.load(io.open(PHRASES, encoding="utf-8"))
    dl = json.load(io.open(DIALOGS, encoding="utf-8"))
    rules = d["rules"]
    by_group = OrderedDict((g, []) for g in GROUPS)
    for r in rules:
        by_group.setdefault(r.get("group", "misc"), []).append(r)

    lines_total = sum(len(r.get("say") or []) for r in rules)
    out = []
    out.append("# The line book")
    out.append("")
    out.append("Every line the follower can say, organised for review against"
               " [voice.md](voice.md). **Generated** by `tools/lines.py` from"
               " the shipped rule file - do not edit here; edit the rules and"
               " re-run. %d rules, %d spoken lines, %d dialog trees."
               % (len(rules), lines_total, len(dl.get("trees", []))))
    out.append("")
    out.append("Each entry: **when** it fires (plain English of the condition"
               " tree), **does** (anything beyond speaking), **pacing**"
               " (priority, cooldown, delay, output), then the lines. `{x}`"
               " in a line is filled at say-time.")
    out.append("")
    out.append("## Contents")
    out.append("")
    for g, blurb in GROUPS.items():
        rs = by_group.get(g, [])
        n = sum(len(r.get("say") or []) for r in rs)
        out.append("- [%s](#%s) - %s (%d rules, %d lines)" % (g, g, blurb, len(rs), n))
    out.append("- [dialogs](#dialogs) - the conversations it starts (%d trees)"
               % len(dl.get("trees", [])))
    out.append("- [talk-to](#talk-to) - the everyday Talk-to script (in code)")
    out.append("")

    for g, blurb in GROUPS.items():
        rs = by_group.get(g, [])
        if not rs:
            continue
        out.append("## %s" % g)
        out.append("")
        out.append("*%s.*" % blurb)
        out.append("")
        for r in rs:
            say = r.get("say") or []
            out.append("### `%s`" % r["id"])
            if r.get("note"):
                out.append("")
                out.append("> %s" % r["note"])
            out.append("")
            out.append("- **when:** %s" % describe(r.get("when")))
            eff = effects(r)
            if eff:
                out.append("- **does:** %s" % "; ".join(eff))
            out.append("- **pacing:** %s" % pacing(r))
            out.append("")
            if say:
                for s in say:
                    out.append("- %s" % s)
            else:
                out.append("- *(no lines - animation or effect only)*")
            out.append("")

    out.append("## dialogs")
    out.append("")
    out.append("*The conversations the follower starts. While one is open,"
               " Talk-to opens it instead of the everyday script; the branch"
               " marked as the answer is what replies.*")
    out.append("")
    for tree in dl.get("trees", []):
        out.append("### `%s`" % tree["id"])
        if tree.get("note"):
            out.append("")
            out.append("> %s" % tree["note"])
        out.append("")
        for node in tree.get("nodes", []):
            label = node["id"]
            if node.get("answer"):
                label += " (answers '%s')" % node["answer"]
            out.append("- **%s**" % label)
            for s in node.get("says") or []:
                out.append("  - Follower: %s" % s)
            for s in node.get("you") or []:
                out.append("  - You: %s" % s)
            for o in node.get("options") or []:
                out.append("  - [%s] -> %s" % (o.get("label"), o.get("next")))
        out.append("")

    out.append("## talk-to")
    out.append("")
    out.append("The everyday Talk-to script is built in code"
               " (`FollowerPlugin.talkScript`) because it branches on live"
               " state - the mood band, the open wish, whether the wished item"
               " is in the bag, today's summary. Its text is reviewed in"
               " `TalkScriptTest`, which walks every structural variant. Read"
               " it there; it is deliberately not duplicated here.")
    out.append("")
    return "\n".join(out) + "\n"


def main():
    text = build()
    if "--check" in sys.argv:
        current = io.open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if current != text:
            print("docs/lines.md is stale; run python tools/lines.py")
            sys.exit(1)
        print("docs/lines.md is current")
        return
    io.open(OUT, "w", encoding="utf-8", newline="\n").write(text)
    print("wrote %s (%d bytes)" % (os.path.relpath(OUT, ROOT), len(text)))


if __name__ == "__main__":
    main()
