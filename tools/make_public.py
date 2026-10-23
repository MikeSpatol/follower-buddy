"""Builds the public (Plugin Hub) tree of Follower Buddy from this checkout.

    python tools/make_public.py --out DIR [--worktree] [--manifest FILE] [--hub-count FILE]

The full source carries the public build's cuts as comments (public/manifest.json
describes the marker language). This tool:

 1. copies the tracked files of HEAD (git archive), or with --worktree the working
    tree's tracked and untracked-not-ignored files, leaving out the manifest's
    "exclude" paths (this tool and the manifest itself);
 2. applies the markers of the manifest's "cut" features and takes every other
    marker out; an unbalanced block, a public:else with no cut before it, a
    malformed marker or an unknown feature name stops it;
 3. runs the manifest's "export_rewrites" (python scripts given the output dir);
 4. drops and moves the cut features' resources, and their fonts in fonts.json;
 5. prunes default-phrases.json: a rule goes when its condition tree names a type
    that is no case in the public Condition.java (nor a key of
    event-conditions.properties, when that file exists), or when it carries a
    field, group, id or tally the manifest's "rules" lists for a cut feature;
 6. regenerates docs/lines.md with tools/lines.py;
 7. checks the result: no leftover names of dropped classes or resources, the
    settings, commands and follower menu entries the manifest expects, and the
    unknown-condition guard wherever a condition type is gone;
 8. counts it with the Hub replica (hub_count.py, given by --hub-count or the
    FOLLOWER_HUB_COUNT environment variable) against the manifest's "budget";
 9. builds it and runs its tests (gradle, build dir DIR/build-public).

Every failure is reported; the exit code is 1 if there was any. DIR must be new,
empty, or an earlier output of this tool (its .git, if any, is kept).
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_DIR = 'build-public'
STAMP = BUILD_DIR + '/make_public.stamp'
MARKED = ('.java', '.md', '.properties')
JAVA = 'src/main/java/com/follower/'
TESTS = 'src/test/java/com/follower/'
RES = 'src/main/resources/com/follower/'
BS = chr(92)


# ------------------------------------------------------------------ markers

NAME = r'[a-z0-9-]+(?:,[a-z0-9-]+)*'


class MarkerError(Exception):
    pass


def _patterns(path):
    if path.endswith('.md'):
        o, c = r'<!--\s*', r'\s*-->'
    elif path.endswith('.properties'):
        o, c = r'#\s*', ''
    else:
        o, c = r'//\s*', ''
    return {
        'cut': re.compile(r'^(\s*)' + o + r'public:cut (' + NAME + r')' + c + r'\s*$'),
        'end': re.compile(r'^(\s*)' + o + r'public:end (' + NAME + r')' + c + r'\s*$'),
        'else': re.compile(r'^(\s*)' + o + r'public:else(?: (.*?))?' + c + r'\s*$'),
        'file': re.compile(r'^' + o + r'public:file (' + NAME + r')' + c + r'\s*$'),
        'trail': re.compile(r'^(.*?\S)\s*' + o + r'public:cut (' + NAME + r')' + c + r'\s*$'),
    }


def apply_markers(text, cut, path, known):
    """The text with the cut features' markers applied and every marker taken out,
    or None when a public:file marker drops the whole file."""
    p = _patterns(path)
    lines = text.split('\n')

    def feats(s, no):
        bad = [f for f in s.split(',') if f not in known]
        if bad:
            raise MarkerError('%s:%d unknown feature %s' % (path, no, ','.join(bad)))
        return set(s.split(','))

    start = 0
    m = p['file'].match(lines[0])
    if m:
        if feats(m.group(1), 1) & cut:
            return None
        start = 1
    out = []
    stack = []          # (feature list, applied, line no)
    dropping = 0        # applied blocks open
    last = None         # whether the cut a public:else binds to applied; None: no cut
    for i in range(start, len(lines)):
        line, no = lines[i], i + 1
        m = p['cut'].match(line)
        if m:
            applied = bool(feats(m.group(2), no) & cut)
            stack.append((m.group(2), applied, no))
            dropping += applied
            last = None
            continue
        m = p['end'].match(line)
        if m:
            if not stack:
                raise MarkerError('%s:%d public:end with no open block' % (path, no))
            name, applied, opened = stack.pop()
            if name != m.group(2):
                raise MarkerError('%s:%d public:end %s closes public:cut %s of line %d'
                                  % (path, no, m.group(2), name, opened))
            dropping -= applied
            last = applied
            continue
        m = p['else'].match(line)
        if m:
            if last is None:
                raise MarkerError('%s:%d public:else without a cut before it' % (path, no))
            if not dropping and last:
                out.append(m.group(1) + (m.group(2) or ''))
            continue
        m = p['trail'].match(line)
        if m:
            applied = bool(feats(m.group(2), no) & cut)
            if not dropping and not applied:
                out.append(m.group(1))
            last = applied
            continue
        # Marker text inside a Java string is data (the tests' PublicMarkers holds the patterns).
        bare = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line) if path.endswith('.java') else line
        if re.search(r'public:(cut|end|else|file)\b', bare):
            raise MarkerError('%s:%d malformed marker: %r' % (path, no, line.strip()[:120]))
        last = None
        if not dropping:
            out.append(line)
    if stack:
        raise MarkerError('%s:%d public:cut %s never closed' % (path, stack[-1][2], stack[-1][0]))
    return '\n'.join(out)


# ------------------------------------------------------------------ java text

def java_code(text):
    """(code, mask): the source without comments, and the same with the insides of
    string and char literals blanked. Structure is read off the mask, values off the
    code at the same offsets."""
    code, mask = [], []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in '"\'':
            j = i + 1
            while j < n and text[j] != c and text[j] != '\n':
                j += 2 if text[j] == BS else 1
            lit = text[i:j + 1]
            code.append(lit)
            mask.append(lit[0] + ' ' * max(0, len(lit) - 2) + (lit[-1] if len(lit) > 1 else ''))
            i = j + 1
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            code.append(' ')
            mask.append(' ')
        elif text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
        else:
            code.append(c)
            mask.append(c)
            i += 1
    return ''.join(code), ''.join(mask)


def closing(mask, i):
    """The offset just past the bracket that closes the one at mask[i]."""
    pair = {'(': ')', '{': '}'}[mask[i]]
    depth = 0
    for j in range(i, len(mask)):
        if mask[j] == mask[i]:
            depth += 1
        elif mask[j] == pair:
            depth -= 1
            if depth == 0:
                return j + 1
    raise ValueError('unclosed %s' % mask[i])


def literal(code, mask, pattern):
    """The string literal that follows the first match of pattern (ending at its
    opening quote) in the mask."""
    m = re.search(pattern, mask)
    if not m:
        return None
    return re.match(r'"((?:[^"\\]|\\.)*)"', code[m.end() - 1:]).group(1)


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


def files(root, top='', ext=''):
    for dp, dn, fs in os.walk(os.path.join(root, top)):
        dn[:] = [d for d in dn if d not in ('.git', '.gradle', BUILD_DIR)]
        for f in fs:
            if f.endswith(ext):
                yield os.path.relpath(os.path.join(dp, f), root).replace(os.sep, '/')


def declared_types(root):
    """Simple names of the classes, interfaces and enums declared in the tree's Java sources."""
    names = set()
    for top in (JAVA, TESTS):
        for rel in files(root, top, '.java'):
            mask = java_code(read(os.path.join(root, rel)))[1]
            names |= set(re.findall(r'\b(?:class|interface|enum)\s+([A-Z]\w*)', mask))
    return names


# ------------------------------------------------------------------ stages

def excluded(rel, exclude):
    return any(rel.rstrip('/') == e.rstrip('/') or (e.endswith('/') and rel.startswith(e)) for e in exclude)


def export(out, worktree, exclude):
    if worktree:
        names = subprocess.run(['git', '-C', REPO, 'ls-files', '-z', '--cached', '--others',
                                '--exclude-standard'], capture_output=True, check=True).stdout
        for rel in names.decode('utf-8').split('\0'):
            src = os.path.join(REPO, rel)
            if rel and not excluded(rel, exclude) and os.path.isfile(src):
                os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
                shutil.copy(src, os.path.join(out, rel))
    else:
        data = subprocess.run(['git', '-C', REPO, 'archive', '--format=tar', 'HEAD'],
                              capture_output=True, check=True).stdout
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            tar.extractall(out, [m for m in tar.getmembers() if not excluded(m.name, exclude)])


def marker_stage(out, cut, known):
    """Applies the markers in place. Returns the dropped files."""
    dropped = []
    for rel in sorted(files(out)):
        path = os.path.join(out, rel)
        if not rel.endswith(MARKED):
            try:
                text = read(path)
            except UnicodeDecodeError:
                continue
            if re.search(r'public:(cut|end|else|file) [a-z]', text):
                raise MarkerError('%s: markers are only read in %s files' % (rel, ', '.join(MARKED)))
            continue
        text = read(path)
        if 'public:' not in text:
            continue
        res = apply_markers(text, cut, rel, known)
        if res is None:
            os.remove(path)
            dropped.append(rel)
            # A folder left empty goes too, as it would in a git checkout.
            folder = os.path.dirname(os.path.normpath(path))
            while folder != out and not os.listdir(folder):
                os.rmdir(folder)
                folder = os.path.dirname(folder)
        else:
            write(path, res)
    return dropped


def resource_stage(out, cut, resources, fail):
    """Drops and moves the cut features' resources. Returns what went, for the name check."""
    gone, moved = [], []
    for feature in sorted(cut):
        r = resources.get(feature, {})
        for rel in r.get('drop', []):
            if not os.path.isfile(os.path.join(out, rel)):
                fail('resources: %s (%s) is not in the tree' % (rel, feature))
                continue
            os.remove(os.path.join(out, rel))
            gone.append(os.path.basename(rel))
        for rel, dest in r.get('move', {}).items():
            if not os.path.isfile(os.path.join(out, rel)):
                fail('resources: %s (%s) is not in the tree' % (rel, feature))
                continue
            os.makedirs(os.path.dirname(os.path.join(out, dest)), exist_ok=True)
            shutil.move(os.path.join(out, rel), os.path.join(out, dest))
            moved.append(os.path.basename(rel))
        if r.get('fonts'):
            path = os.path.join(out, RES, 'fonts.json')
            text = read(path)
            data = json.loads(text)
            if json.dumps(data, separators=(',', ':'), ensure_ascii=False) != text:
                fail('resources: fonts.json is not in the compact form this tool writes')
                continue
            names = [f['name'] for f in data['fonts']]
            for name in r['fonts']:
                if name not in names:
                    fail('resources: font %s (%s) is not in fonts.json' % (name, feature))
            data['fonts'] = [f for f in data['fonts'] if f['name'] not in r['fonts']]
            write(path, json.dumps(data, separators=(',', ':'), ensure_ascii=False))
            gone += r['fonts']
    return gone, moved


def live_types(out):
    """The condition types the tree's Condition evaluates, lower case."""
    code = java_code(read(os.path.join(out, JAVA, 'speech/Condition.java')))[0]
    live = {m.lower() for m in re.findall(r'case\s+"([^"]+)"\s*:', code)}
    events = os.path.join(out, RES, 'event-conditions.properties')
    if os.path.isfile(events):
        for line in read(events).split('\n'):
            line = line.strip()
            if line and line[0] not in '#!':
                live.add(re.split(r'\s*[=:\s]', line, 1)[0].lower())
    return live


def condition_types(node, out, tallies):
    if isinstance(node, dict):
        if isinstance(node.get('type'), str):
            out.add(node['type'].lower())
            if node['type'].lower() == 'tally' and isinstance(node.get('of'), str):
                tallies.add(node['of'])
        for v in node.values():
            condition_types(v, out, tallies)
    elif isinstance(node, list):
        for v in node:
            condition_types(v, out, tallies)


def prune_stage(out, cut, rules_cfg, fail):
    """Drops the bundled rules whose code is gone. Returns (kept, [(id, why)])."""
    want = {k: set() for k in ('fields', 'groups', 'ids', 'tallies')}
    for feature in cut:
        for k, v in rules_cfg.get(feature, {}).items():
            want[k].update(v)
    live = live_types(out)
    path = os.path.join(out, RES, 'default-phrases.json')
    text = read(path)
    data = json.loads(text)
    if json.dumps(data, indent=2, ensure_ascii=False) + '\n' != text:
        fail('rules: default-phrases.json is not in the indent-2 form this tool writes')
        return len(data['rules']), []
    kept, dropped, matched = [], [], set()
    for r in data['rules']:
        types, tallies = set(), set()
        condition_types(r.get('when'), types, tallies)
        why = sorted(types - live)
        for k, hits in (('fields', want['fields'] & set(r)),
                        ('groups', want['groups'] & {r.get('group')}),
                        ('ids', want['ids'] & {r.get('id')}),
                        ('tallies', want['tallies'] & tallies)):
            matched |= {(k, h) for h in hits}
            why += sorted(hits)
        if why:
            dropped.append((r.get('id'), why))
        else:
            kept.append(r)
    for k, names in want.items():
        for name in sorted(names):
            if (k, name) not in matched:
                fail('rules: the manifest prunes %s %s, which no rule has' % (k, name))
    data['rules'] = kept
    write(path, json.dumps(data, indent=2, ensure_ascii=False) + '\n')
    return len(kept), dropped


# ------------------------------------------------------------------ checks

def check_names(out, dropped_types, gone, moved, fail):
    """No public source names a dropped class, a dropped resource or font, and no
    main source a resource that moved to the tests."""
    words = re.compile(r'\b(' + '|'.join(sorted(dropped_types)) + r')\b') if dropped_types else None
    for top in (JAVA, TESTS):
        for rel in files(out, top, '.java'):
            code, mask = java_code(read(os.path.join(out, rel)))
            if words:
                for name in sorted(set(words.findall(mask))):
                    fail('names: %s uses %s, a class the public build drops' % (rel, name))
            for name in gone + (moved if top == JAVA else []):
                if name in code:
                    fail('names: %s names %s, which the public build %s'
                         % (rel, name, 'drops' if name in gone else 'moves to the tests'))


def settings(out):
    """{section name: sorted keyNames} of the tree's FollowerConfig; "" for no section."""
    code, mask = java_code(read(os.path.join(out, JAVA, 'FollowerConfig.java')))
    sections = {}
    for m in re.finditer(r'@ConfigSection\(', mask):
        end = closing(mask, m.end() - 1)
        field = re.match(r'\s*String\s+(\w+)', mask[end:]).group(1)
        sections[field] = literal(code[m.start():end], mask[m.start():end], r'\bname\s*=\s*"')
    found = {name: [] for name in sections.values()}
    for m in re.finditer(r'@ConfigItem\(', mask):
        end = closing(mask, m.end() - 1)
        key = literal(code[m.start():end], mask[m.start():end], r'\bkeyName\s*=\s*"')
        sec = re.search(r'\bsection\s*=\s*(\w+)', mask[m.start():end])
        found.setdefault(sections[sec.group(1)] if sec else '', []).append(key)
    return {name: sorted(keys) for name, keys in found.items()}


def commands(out):
    """The everyday command list's commands and the ::follower switch's own case labels."""
    code, mask = java_code(read(os.path.join(out, JAVA, 'FollowerPlugin.java')))
    at = mask.index('EVERYDAY_COMMANDS =')
    listed = re.findall(r'\bcommand\("([a-z]+)', code[at:mask.index(';', at)])
    start = mask.index('{', mask.index('switch (sub)', mask.index('void onCommandExecuted(')))
    end = closing(mask, start)
    handled = []
    for m in re.finditer(r'\bcase\s+"', mask[start:end]):
        inner = mask[start:start + m.start()]
        if inner.count('{') - inner.count('}') == 1:
            handled.append(literal(code[start + m.start():], mask[start + m.start():], r'case\s+"'))
    return {'everyday': sorted(listed), 'handled': sorted(handled)}


def menu(out):
    """The options of the follower's own menu entries."""
    code, mask = java_code(read(os.path.join(out, JAVA, 'FollowerPlugin.java')))
    found = set()
    # addFollowerMenuEntry may take the entry's index first: (1, "Examine", ...).
    for m in re.finditer(r'(?:addFollowerMenuEntry|\.setOption)\(\s*(?:-?\d+\s*,\s*)?"', mask):
        found.add(literal(code[m.start():], mask[m.start():], r'\(\s*(?:-?\d+\s*,\s*)?"'))
    return sorted(found)


def compare(what, expected, found, fail):
    if expected is None:
        fail('%s: the manifest has no expected list; found %s' % (what, json.dumps(found)))
    elif expected != found:
        fail('%s: expected %s, found %s' % (what, json.dumps(expected), json.dumps(found)))


# ------------------------------------------------------------------ build

def run_build(out, fail):
    gradlew = os.path.join(out, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
    code = subprocess.run([gradlew, '-q', 'build', '-PaltBuild=' + BUILD_DIR], cwd=out).returncode
    if code:
        fail('build: gradle exited %d' % code)
    totals = [0, 0, 0, 0]
    failed = []
    results = os.path.join(out, BUILD_DIR, 'test-results', 'test')
    for rel in files(results, '', '.xml'):
        suite = ET.parse(os.path.join(results, rel)).getroot()
        for i, k in enumerate(('tests', 'failures', 'errors', 'skipped')):
            totals[i] += int(suite.get(k, 0))
        for case in suite.iter('testcase'):
            if case.find('failure') is not None or case.find('error') is not None:
                failed.append('%s.%s' % (case.get('classname'), case.get('name')))
    for name in failed:
        fail('tests: %s failed' % name)
    return totals


def hub_count(out, counter, fail):
    if not counter or not os.path.isfile(counter):
        fail('budget: no Hub counter; give --hub-count or FOLLOWER_HUB_COUNT (hub_count.py)')
        return None
    res = subprocess.run([sys.executable, counter, out, '0'], capture_output=True, text=True)
    m = re.search(r'hub_estimate (\d+)', res.stdout)
    if not m:
        fail('budget: the Hub counter failed: %s' % (res.stderr or res.stdout).strip()[-300:])
        return None
    return int(m.group(1))


# ------------------------------------------------------------------ main

def prepare(out):
    out = os.path.abspath(out)
    if os.path.normcase(out + os.sep).startswith(os.path.normcase(REPO + os.sep)) or \
            os.path.normcase(out) == os.path.normcase(REPO):
        sys.exit('make_public: --out must be outside the repository')
    if os.path.isdir(out) and set(os.listdir(out)) - {'.git'}:
        if not os.path.isfile(os.path.join(out, STAMP)):
            sys.exit('make_public: %s is not empty and is not an earlier output; not clearing it' % out)
        for name in os.listdir(out):
            path = os.path.join(out, name)
            if name == '.git':
                continue
            if os.path.isdir(path) and not os.path.islink(path):
                shutil.rmtree(path)
            else:
                os.remove(path)
    os.makedirs(os.path.join(out, BUILD_DIR), exist_ok=True)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--out', required=True, help='directory for the public tree')
    ap.add_argument('--worktree', action='store_true', help='export the working tree instead of HEAD')
    ap.add_argument('--manifest', help='manifest to use instead of the exported public/manifest.json')
    ap.add_argument('--hub-count', default=os.environ.get('FOLLOWER_HUB_COUNT'),
                    help='path of hub_count.py, the Hub bot replica')
    args = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    if args.manifest:
        manifest = json.loads(read(args.manifest))
    elif args.worktree:
        manifest = json.loads(read(os.path.join(REPO, 'public/manifest.json')))
    else:
        manifest = json.loads(subprocess.run(['git', '-C', REPO, 'show', 'HEAD:public/manifest.json'],
                                             capture_output=True, check=True).stdout.decode('utf-8'))
    known = set(manifest['features'])
    cut = set(manifest['cut'])
    for f in sorted(cut - known):
        sys.exit('make_public: the manifest cuts %s, which is no feature' % f)
    for f in sorted(cut):
        for needed in manifest.get('requires', {}).get(f, []):
            if needed not in cut:
                sys.exit('make_public: cutting %s needs %s cut too (manifest "requires")' % (f, needed))

    out = prepare(args.out)
    source = 'the working tree' if args.worktree else 'HEAD ' + subprocess.run(
        ['git', '-C', REPO, 'rev-parse', '--short', 'HEAD'], capture_output=True, text=True).stdout.strip()
    write(os.path.join(out, STAMP), 'public build of %s, cut %s\n' % (source, ','.join(sorted(cut))))
    print('make_public: %s, cut %s' % (source, ','.join(sorted(cut))))

    failures = []

    def fail(message):
        failures.append(message)
        print('  FAIL ' + message)

    export(out, args.worktree, manifest.get('exclude', []))
    full_types = declared_types(out)
    full_live = live_types(out)
    try:
        dropped_files = marker_stage(out, cut, known)
    except MarkerError as e:
        sys.exit('make_public: marker error: %s' % e)
    print('  markers: %d files dropped' % len(dropped_files))

    for script in manifest.get('export_rewrites', []):
        if subprocess.run([sys.executable, os.path.join(REPO, script), out]).returncode:
            fail('rewrite: %s failed' % script)
    print('  export rewrites: %d' % len(manifest.get('export_rewrites', [])))

    gone, moved = resource_stage(out, cut, manifest.get('resources', {}), fail)
    print('  resources: dropped %s; moved %s' % (', '.join(gone) or '-', ', '.join(moved) or '-'))

    kept, pruned = prune_stage(out, cut, manifest.get('rules', {}), fail)
    print('  rules: %d kept, %d pruned' % (kept, len(pruned)))
    for rid, why in pruned:
        print('    %-44s %s' % (rid, ','.join(why)))

    if subprocess.run([sys.executable, os.path.join(out, 'tools', 'lines.py')], cwd=out,
                      stdout=subprocess.DEVNULL).returncode:
        fail('lines: tools/lines.py failed')

    check_names(out, full_types - declared_types(out), gone, moved, fail)
    expect = manifest.get('expect', {})
    compare('settings', expect.get('settings'), settings(out), fail)
    compare('commands', expect.get('commands'), commands(out), fail)
    compare('menu', expect.get('menu'), menu(out), fail)
    gone_types = sorted(full_live - live_types(out))
    condition = read(os.path.join(out, JAVA, 'speech/Condition.java'))
    if gone_types and 'UNKNOWN_TYPES' not in condition:
        fail('guard: condition types %s are gone but the unknown-type guard is not in the'
             ' build; add the feature to its markers in Condition.java' % ','.join(gone_types))

    hub = hub_count(out, args.hub_count, fail)
    budget = manifest.get('budget')
    if hub is not None:
        print('  hub count: %d (budget %d)' % (hub, budget))
        if hub > budget:
            fail('budget: hub count %d is over the budget of %d by %d' % (hub, budget, hub - budget))

    tests, failed, errors, skipped = run_build(out, fail)
    print('  tests: %d run, %d failures, %d errors, %d skipped' % (tests, failed, errors, skipped))

    if failures:
        print('make_public: %d check(s) failed' % len(failures))
        for message in failures:
            print('  ' + message)
        sys.exit(1)
    print('make_public: ok, public tree in %s' % out)


if __name__ == '__main__':
    main()
