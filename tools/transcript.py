"""Read a follower transcript and say what the session actually sounded like.

Every tuning judgement in this project used to rest on an impression and a
model. The transcript is the record; this is the thing that reads it, so that
two rounds of testing a week apart are measured the same way rather than
however the analysis happened to be written that day.

    python tools/transcript.py                     # today's, from ~/.runelite
    python tools/transcript.py path/to/file.log
    python tools/transcript.py --session -1        # only the last session

Columns are: time, kind, rule id, group, region, and then the line itself for
a "said" or the reason for a "held".
"""

import collections
import datetime
import glob
import io
import os
import sys

MARKER = "---- transcript opened ----"


def find_default():
    home = os.path.expanduser("~")
    pattern = os.path.join(home, ".runelite", "follower", "transcript-*.log")
    files = sorted(glob.glob(pattern))
    if not files:
        sys.exit("no transcript found under %s" % pattern)
    return files[-1]


def parse(path):
    """Splits into sessions, each a list of row dicts."""
    sessions, current = [], []
    for raw in io.open(path, encoding="utf-8", errors="replace"):
        raw = raw.rstrip("\n")
        if raw.startswith(MARKER):
            if current:
                sessions.append(current)
            current = []
            continue
        parts = raw.split("\t")
        if len(parts) < 4:
            continue
        # The region column was added partway through; older rows are shorter.
        if len(parts) >= 6:
            time, kind, rule, group, region, tail = parts[:6]
        else:
            time, kind, rule, group, tail = (parts + [""])[:5]
            region = ""
        current.append({"time": time, "kind": kind, "rule": rule,
                        "group": group, "region": region, "tail": tail})
    if current:
        sessions.append(current)
    return sessions


def minutes_between(first, last):
    fmt = "%H:%M:%S"
    try:
        a = datetime.datetime.strptime(first, fmt)
        b = datetime.datetime.strptime(last, fmt)
    except ValueError:
        return 0.0
    span = (b - a).total_seconds()
    if span < 0:            # rolled past midnight
        span += 24 * 3600
    return span / 60.0


def report(rows, label):
    said = [r for r in rows if r["kind"] == "said"]
    held = [r for r in rows if r["kind"] == "held"]
    if not rows:
        return
    span = minutes_between(rows[0]["time"], rows[-1]["time"])

    print("\n=== %s ===" % label)
    print("%s to %s  (%.0f min)" % (rows[0]["time"], rows[-1]["time"], span))
    print("%d spoken, %d held back" % (len(said), len(held)))
    if span > 0:
        print("%.1f lines an hour" % (len(said) * 60.0 / span))

    if held:
        print("\nwhy it stayed quiet:")
        for reason, n in collections.Counter(r["tail"] for r in held).most_common():
            print("  %-10s %4d  (%.0f%% of everything that won)"
                  % (reason, n, 100.0 * n / (len(said) + len(held))))

    print("\nwhat it talked about:")
    for group, n in collections.Counter(r["group"] for r in said).most_common(8):
        print("  %-12s %4d  %.0f%%" % (group, n, 100.0 * n / len(said)))

    print("\nbusiest rules:")
    for rule, n in collections.Counter(r["rule"] for r in said).most_common(8):
        print("  %-28s %3d" % (rule, n))

    # The thing the shuffle bag exists to prevent. A line coming round again
    # inside a dozen is the failure a player actually notices.
    seen, repeats = {}, []
    for i, r in enumerate(said):
        line = r["tail"]
        if line in seen and i - seen[line] <= 12:
            repeats.append((i - seen[line], r["rule"], line))
        seen[line] = i
    print("\nlines repeated within a dozen: %d" % len(repeats))
    for gap, rule, line in repeats[:8]:
        print("  %d apart  %-24s %s" % (gap, rule, line[:52]))

    # An occasion held back is the one failure the director must never cause.
    swallowed = [r for r in held if r["tail"] in ("relax", "settling")]
    if swallowed:
        print("\nheld by the director (%d):" % len(swallowed))
        for rule, n in collections.Counter(r["rule"] for r in swallowed).most_common(8):
            print("  %-28s %3d" % (rule, n))

    gaps = []
    for i in range(1, len(said)):
        gaps.append((minutes_between(said[i - 1]["time"], said[i]["time"]),
                     said[i - 1]["time"], said[i]["time"]))
    if gaps:
        gaps.sort(reverse=True)
        print("\nlongest silences: %s"
              % ", ".join("%.0f min (%s)" % (g, a) for g, a, _ in gaps[:5]))


def main():
    args = [a for a in sys.argv[1:]]
    which = None
    if "--session" in args:
        at = args.index("--session")
        which = int(args[at + 1])
        del args[at:at + 2]
    path = args[0] if args else find_default()

    sessions = parse(path)
    print("%s: %d session(s)" % (os.path.basename(path), len(sessions)))
    if which is not None:
        report(sessions[which], "session %d" % which)
    else:
        for i, rows in enumerate(sessions):
            report(rows, "session %d of %d" % (i + 1, len(sessions)))
        if len(sessions) > 1:
            report([r for s in sessions for r in s], "everything together")


if __name__ == "__main__":
    main()
