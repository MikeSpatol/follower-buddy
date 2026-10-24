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
 3. runs the manifest's "export_rewrites", by name and in order (REWRITES below:
    denser Java that compiles to the same classes), then lets the tests' quotes of
    the main source text follow what they changed (follow_tests);
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
MARKED = ('.java', '.md', '.properties', '.tsv')
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
    elif path.endswith(('.properties', '.tsv')):
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


def java_file(out, name):
    """The main source file of that name, wherever below com/follower it is."""
    for rel in files(out, JAVA, '.java'):
        if rel.endswith('/' + name):
            return os.path.join(out, rel)
    raise FileNotFoundError(name)


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


# ------------------------------------------------------------------ export rewrites
#
# Rewrites of the public tree's Java into a denser form that compiles to the same
# thing. The full source keeps its own style; only the copy is rewritten. They run in
# the manifest's "export_rewrites" order (REWRITES below holds them by name), each on
# the tree the one before it left. Each one's docstring says what in the compiled
# classes it may change; everything else must stay identical (javap -c -p). They work
# on the text, so the public build's javac and tests are what catch a slip.

MAIN = 'src/main/java/'
TOKEN = re.compile(r'[A-Za-z_][A-Za-z0-9_]*|\d+|\S')
IMPORT_LINE = r'^import\s+[\w.]+\s*;[^\n]*\n'


def ntok(text):
    """A rough token count, to decide whether a rewrite pays for an import line."""
    return len(TOKEN.findall(text))


def code_mask(s):
    """For each character of s, whether it is code (outside comments and literals)."""
    m = [True] * len(s)
    i, n = 0, len(s)
    while i < n:
        if s.startswith('"""', i):
            j = s.find('"""', i + 3)
            j = n if j < 0 else j + 3
        elif s[i] in '"\'':
            q = s[i]
            j = i + 1
            while j < n and s[j] != q:
                j += 2 if s[j] == BS else 1
            j += 1
        elif s.startswith('/*', i):
            j = s.find('*/', i + 2)
            j = n if j < 0 else j + 2
        elif s.startswith('//', i):
            j = s.find('\n', i)
            j = n if j < 0 else j
        else:
            i += 1
            continue
        for k in range(i, min(j, n)):
            m[k] = False
        i = j
    return m


def strip_comments(s):
    """s without its comments (a block comment leaves a space); literals kept."""
    out = []
    i, n = 0, len(s)
    while i < n:
        if s.startswith('"""', i):
            j = s.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(s[i:j])
            i = j
            continue
        if s[i] in '"\'':
            q = s[i]
            j = i + 1
            while j < n and s[j] != q:
                j += 2 if s[j] == BS else 1
            out.append(s[i:j + 1])
            i = j + 1
            continue
        if s.startswith('/*', i):
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 2
            out.append(' ')
            continue
        if s.startswith('//', i):
            j = s.find('\n', i)
            i = n if j < 0 else j
            continue
        out.append(s[i])
        i += 1
    return ''.join(out)


def blank(s, keep_quotes=True):
    """s with comments and the insides of literals blanked to spaces, newlines kept; with
    keep_quotes a literal's own quotes stay, so "" still marks a string."""
    m = code_mask(s)
    n = len(s)

    def delim(i):
        return (i > 0 and m[i - 1]) or (i + 1 < n and m[i + 1])
    return ''.join(c if (m[i] or c == '\n' or (keep_quotes and c in '"\'' and delim(i))) else ' '
                   for i, c in enumerate(s))


def kinds(b):
    """For blanked code b, the innermost context at each offset: 'top', 'class' (a class
    body, where members are declared), 'code' (a method body, initialiser or array
    initialiser) or 'paren'."""
    out = ['top'] * (len(b) + 1)
    st = []
    seg_start = 0
    for i, c in enumerate(b):
        cur = st[-1] if st else 'top'
        out[i] = cur
        if c == '{':
            head = b[seg_start:i]
            if re.search(r'\b(class|interface|enum)\b', head) and not re.search(r'[=(]\s*$', head.strip()[-2:] or ''):
                k = 'class'
            elif re.search(r'\bnew\s+[\w.<>, ?\[\]]+\s*\([^;{}]*\)\s*$', head):
                k = 'class'   # an anonymous class body
            elif cur == 'class' and re.search(r'=\s*$', head):
                k = 'code'    # an array initialiser at member level
            elif cur == 'class' and re.match(r'\s*(static\s*)?$', head):
                k = 'code'    # an initialiser block
            elif cur == 'class' and re.search(r'\)\s*(throws[\w\s,.]+)?$', head):
                k = 'code'    # a method or constructor body
            elif cur == 'class' and re.search(r'\b[A-Z_][A-Z0-9_]*\s*(\([^;{}]*\))?\s*$', head):
                k = 'class'   # an enum constant's body
            else:
                k = 'code'
            st.append(k)
            seg_start = i + 1
        elif c == '}':
            if st:
                st.pop()
            seg_start = i + 1
        elif c == ';':
            seg_start = i + 1
        elif c == '(':
            st.append('paren')
        elif c == ')':
            if st and st[-1] == 'paren':
                st.pop()
    return out


def owners(s, mask):
    """For each offset, the offset of the innermost '{' around it (-1 at top level)."""
    own = [-1] * (len(s) + 1)
    st = []
    for i, c in enumerate(s):
        own[i] = st[-1] if st else -1
        if mask[i]:
            if c == '{':
                st.append(i)
            elif c == '}' and st:
                st.pop()
    return own


def add_imports(s, lines):
    """s with the import lines added after its last import (or its package line)."""
    if not lines:
        return s
    im = list(re.finditer(IMPORT_LINE, s, re.M))
    if im:
        at = im[-1].end()
        return s[:at] + ''.join(l + '\n' for l in lines) + s[at:]
    at = re.search(r'^package[^\n]*\n', s, re.M).end()
    return s[:at] + '\n' + ''.join(l + '\n' for l in lines) + s[at:]


def standalone_use(s, mask, simple, spans):
    """Whether simple appears in s's code outside spans, unqualified and not in an
    import or package line: a name the file already uses for something."""
    for m in re.finditer(r'\b%s\b' % re.escape(simple), s):
        i = m.start()
        if not mask[i] or any(a <= i < b for a, b in spans):
            continue
        ls = s.rfind('\n', 0, i) + 1
        if re.match(r'\s*(import|package)\b', s[ls:i]):
            continue
        j = i - 1
        while j >= 0 and s[j] in ' \t\r\n':
            j -= 1
        if j >= 0 and s[j] == '.':
            continue
        return True
    return False


def each_java(out, top=MAIN):
    for rel in files(out, top, '.java'):
        yield os.path.join(out, rel)


def rewrite_files(out, edit, top=MAIN):
    """Runs edit(path, text) -> new text on every Java file under top; returns how many changed."""
    changed = 0
    for path in each_java(out, top):
        text = read(path)
        new = edit(path, text)
        if new != text:
            write(path, new)
            changed += 1
    return changed


FQN_ROOTS = ('java', 'javax', 'net', 'com', 'org', 'lombok')
FQN = re.compile(r'(?<![\w.])((?:%s)(?:\s*\.\s*[a-z_][a-z0-9_]*)*)((?:\s*\.\s*[A-Z][A-Za-z0-9_]*)+)'
                 % '|'.join(FQN_ROOTS))
TYPE_SEGMENT = re.compile(r'^[A-Z](?:[a-z0-9][A-Za-z0-9_]*)?$|^[A-Z][A-Z]?[a-z][A-Za-z0-9_]*$')


def packages(out):
    """{package: set of its top-level type names} of the main sources."""
    pkgs = {}
    for path in each_java(out):
        rel = os.path.relpath(os.path.dirname(path), os.path.join(out, MAIN))
        pkgs.setdefault(rel.replace(os.sep, '.'), set()).add(os.path.basename(path)[:-5])
    return pkgs


def rw_fqn(out):
    """Fully qualified type names in code become imports, per file and import target,
    where that saves tokens net of the import line and the simple name cannot clash.
    Compiled classes: identical."""
    pkgs = packages(out)

    def edit(path, s):
        mask = code_mask(s)
        pm = re.search(r'^package\s+([\w.]+)\s*;', s, re.M)
        pkg = pm.group(1) if pm else ''
        imports = {m.group(1).rsplit('.', 1)[1]: m.group(1)
                   for m in re.finditer(r'^import\s+([\w.]+)\s*;', s, re.M)}
        declared = set(re.findall(r'\b(?:class|interface|enum|record)\s+([A-Z]\w*)',
                                  ''.join(c if mask[i] else ' ' for i, c in enumerate(s))))
        same_pkg = pkgs.get(pkg, set())
        own = os.path.basename(path)[:-5]
        occ = []
        for m in FQN.finditer(s):
            if not mask[m.start()]:
                continue
            ls = s.rfind('\n', 0, m.start()) + 1
            if re.match(r'\s*(import|package)\b', s[ls:m.start() + 1]):
                continue
            segs = re.sub(r'\s+', '', m.group(2)).lstrip('.').split('.')
            types = segs[:1]  # what follows a package is always a type
            for g in segs[1:]:
                if not TYPE_SEGMENT.match(g):
                    break
                types.append(g)
            occ.append(dict(start=m.start(), end=m.end(), pkg=re.sub(r'\s+', '', m.group(1)),
                            types=types, rest=segs[len(types):], text=m.group(0)))
        if not occ:
            return s
        spans = [(o['start'], o['end']) for o in occ]

        def ok(target, simple, chosen):
            if imports.get(simple) == target:
                return 'have'
            if simple in imports or simple in declared:
                return None
            if any(t != target and n[0] == simple for t, n in chosen.items()):
                return None
            if target.rsplit('.', 1)[0] == pkg:  # a top-level class of this package
                return 'have'
            if simple in same_pkg or simple == own or standalone_use(s, mask, simple, spans):
                return None
            return 'new'

        # Each occurrence takes its outermost type (Outer, not Outer.Inner); a target
        # that does not pay for its import is given up, and the choice made again.
        depth = {i: 1 for i in range(len(occ))}
        for _ in range(10):
            chosen, groups = {}, {}
            for i, o in enumerate(occ):
                d = depth[i]
                if d == 0:
                    continue
                t = o['pkg'] + '.' + '.'.join(o['types'][:d])
                groups.setdefault(t, []).append(i)
                chosen.setdefault(t, o['types'][d - 1])
            final = {}
            changed = False
            for t in sorted(groups, key=lambda t: -len(groups[t])):
                simple = chosen[t]
                st = ok(t, simple, final)
                gain = sum(ntok(occ[i]['text']) - ntok('.'.join(occ[i]['types'][depth[i] - 1:] + occ[i]['rest']))
                           for i in groups[t])
                cost = 0 if st == 'have' else ntok('import %s;' % t)
                if st is None or gain - cost <= 0:
                    for i in groups[t]:
                        depth[i] -= 1
                    changed = True
                    break
                final[t] = (simple, st)
            if not changed:
                break
        edits = []
        for t in final:
            for i in groups[t]:
                o = occ[i]
                edits.append((o['start'], o['end'], '.'.join(o['types'][depth[i] - 1:] + o['rest'])))
        if not edits:
            return s
        for a, b, r in sorted(edits, reverse=True):
            s = s[:a] + r + s[b:]
        return add_imports(s, ['import %s;' % t for t in sorted(t for t, (_, st) in final.items() if st == 'new')])
    return '%d files' % rewrite_files(out, edit)


def rw_nested(out):
    """Outer.Inner becomes Inner plus an import (or nothing, for the file's own nested
    types) where that pays, for nested types of the main sources and Map.Entry. A file's
    own nested types keep their qualifier in its top-level type's header (annotations,
    extends, implements), where they are not in scope. Compiled classes: identical."""
    tops = {}
    for path in each_java(out):
        s = read(path)
        mask = code_mask(s)
        code = ''.join(c if mask[i] else ' ' for i, c in enumerate(s))
        pkg = re.search(r'^package\s+([\w.]+)\s*;', s, re.M).group(1)
        own = os.path.basename(path)[:-5]
        tops[pkg + '.' + own] = set(re.findall(r'\b(?:class|interface|enum|record)\s+([A-Z]\w*)', code)) - {own}
    pkgs = {}
    for fq in tops:
        p, n = fq.rsplit('.', 1)
        pkgs.setdefault(p, set()).add(n)
    lib = {'java.util.Map': {'Entry'}}

    def edit(path, s):
        mask = code_mask(s)
        pkg = re.search(r'^package\s+([\w.]+)\s*;', s, re.M).group(1)
        own = os.path.basename(path)[:-5]
        imports = {m.group(1).rsplit('.', 1)[1]: m.group(1) for m in re.finditer(r'^import\s+([\w.]+)\s*;', s, re.M)}
        code = ''.join(c if mask[i] else ' ' for i, c in enumerate(s))
        declared = set(re.findall(r'\b(?:class|interface|enum|record)\s+([A-Z]\w*)', code))

        def resolve(outer):
            if outer in imports:
                return imports[outer]
            if outer in pkgs.get(pkg, ()):
                return pkg + '.' + outer
            return None

        top = re.search(r'\b(?:class|interface|enum|record)\s+%s\b' % re.escape(own), code)
        head_from = max([m.end() for m in re.finditer(r'^(?:import|package)\b[^;]*;', code, re.M)] or [0])
        head_to = code.find('{', top.end()) if top else -1
        occ = {}
        for m in re.finditer(r'(?<![\w.])([A-Z]\w*)\s*\.\s*([A-Z][a-z]\w*)\b(?!\s*\()', s):
            if not mask[m.start()]:
                continue
            if head_from <= m.start() < head_to and resolve(m.group(1)) == pkg + '.' + own:
                continue
            ls = s.rfind('\n', 0, m.start()) + 1
            if re.match(r'\s*(import|package)\b', s[ls:m.start() + 1]):
                continue
            outer, inner = m.group(1), m.group(2)
            fq = resolve(outer)
            if not fq or inner not in (lib.get(fq) or tops.get(fq, set())):
                continue
            occ.setdefault((fq, inner), []).append((m.start(), m.end()))
        spans = [sp for v in occ.values() for sp in v]
        chosen = {}
        for (fq, inner), sp in sorted(occ.items(), key=lambda kv: -len(kv[1])):
            if any(n == inner for n in chosen.values()):
                continue
            if fq == pkg + '.' + own:
                if inner in imports:
                    continue
                cost = 0
            else:
                if (inner in imports or inner in declared or inner in pkgs.get(pkg, ())
                        or standalone_use(s, mask, inner, spans)):
                    continue
                cost = ntok('import %s.%s;' % (fq, inner))
            if 2 * len(sp) - cost <= 0:
                continue
            chosen[(fq, inner)] = inner
        if not chosen:
            return s
        edits = [(a, b, inner) for (fq, inner) in chosen for a, b in occ[(fq, inner)]]
        for a, b, r in sorted(edits, reverse=True):
            s = s[:a] + r + s[b:]
        return add_imports(s, sorted('import %s.%s;' % (fq, inner) for fq, inner in chosen if fq != pkg + '.' + own))
    return '%d files' % rewrite_files(out, edit)


LOMBOK_MODS = r'((?:(?:public|protected|private|static|final|synchronized)\s+)*)'
GETTER = re.compile(r'(?m)^[ \t]*(?:@Override\s+)?' + LOMBOK_MODS + r'([\w.<>\[\], ?]+?)\s+(get|is)([A-Z]\w*)'
                    r'\s*\(\s*\)\s*\{\s*return\s+(?:this\s*\.\s*)?(\w+)\s*;\s*\}[ \t]*\r?\n')
SETTER = re.compile(r'(?m)^[ \t]*(?:@Override\s+)?' + LOMBOK_MODS + r'void\s+set([A-Z]\w*)\s*\(\s*(?:final\s+)?'
                    r'([\w.<>\[\], ?]+?)\s+(\w+)\s*\)\s*\{\s*(this\s*\.\s*)?(\w+)\s*=\s*(\w+)\s*;\s*\}[ \t]*\r?\n')
FIELD_OF = (r'(?m)^([ \t]*)((?:@\w+(?:\([^)\n]*\))?\s+)*)((?:(?:public|protected|private|static|final|volatile'
            r'|transient)\s+)*)%s\s+%s\s*(=|;)')
CONSTRUCTOR = re.compile(r'(?m)^[ \t]*((?:@\w+\s+)*)((?:public|protected|private)\s+)?([A-Z]\w*)\s*\(([^()]*)\)'
                         r'\s*\{((?:\s*this\s*\.\s*\w+\s*=\s*\w+\s*;)+)\s*\}[ \t]*\r?\n')
FIELD_DECL = re.compile(r'(?m)^[ \t]*(?:@\w+(?:\([^)\n]*\))?\s+)*((?:(?:public|protected|private|static|final'
                        r'|volatile|transient)\s+)*)([\w.<>\[\], ?]+?)\s+(\w+)\s*(=|;)')


def removal_start(s, j):
    """Where removing a member that starts at j begins: a comment block directly above
    it goes too, and one blank line before that."""
    k = s.rfind('\n', 0, j - 1) + 1
    if s[k:j].strip().endswith('*/'):
        c = max(s.rfind('/**', 0, j), s.rfind('/*', 0, j))
        if c >= 0 and s[s.rfind('\n', 0, c) + 1:c].strip() == '':
            j = s.rfind('\n', 0, c) + 1
    if s[s.rfind('\n', 0, j - 1) + 1:j].strip() == '':
        j = s.rfind('\n', 0, j - 1) + 1
    return j


def rw_lombok(out):
    """Trivial getters and setters become @Getter/@Setter on their field, and constructors
    that only assign their parameters to the fields Lombok would take, in order, become
    @RequiredArgsConstructor/@AllArgsConstructor (an @Inject moves to onConstructor_).
    Compiled classes: the same members, but a generated accessor or constructor is public
    where the hand-written one had less access."""
    def norm(t):
        return re.sub(r'\s+', '', t)

    def accessors(path, s):
        mask = code_mask(s)
        own = owners(s, mask)
        removals, annots = [], {}

        def lead(m):
            i = m.start()
            while s[i] in ' \t':
                i += 1
            return i

        def field(typ, name, at):
            hits = []
            for f in re.finditer(FIELD_OF % (r'([\w.<>\[\], ?]+?)', re.escape(name)), s):
                p = f.start() + len(f.group(1))
                if norm(f.group(4)) == norm(typ) and mask[p] and own[p] == own[at]:
                    hits.append((f, p))
            return hits[0] if len(hits) == 1 else None

        for m in GETTER.finditer(s):
            i = lead(m)
            mods, typ, pre, cap, ret = m.groups()
            if not mask[i] or ret != cap[0].lower() + cap[1:] or (pre == 'is') != (norm(typ) == 'boolean'):
                continue
            hit = field(typ, ret, i)
            if hit and ('static' in mods) == ('static' in hit[0].group(3)):
                removals.append((m.start(), m.end()))
                annots.setdefault(hit[1], set()).add('Getter')
        for m in SETTER.finditer(s):
            i = lead(m)
            mods, cap, typ, param, this, lhs, rhs = m.groups()
            name = cap[0].lower() + cap[1:]
            if not mask[i] or lhs != name or rhs != param or (param == name and not this):
                continue
            hit = field(typ, name, i)
            if hit and 'final' not in hit[0].group(3) and ('static' in mods) == ('static' in hit[0].group(3)):
                removals.append((m.start(), m.end()))
                annots.setdefault(hit[1], set()).add('Setter')
        if not removals:
            return s
        edits = [(removal_start(s, a), b, '') for a, b in removals]
        for p, ks in annots.items():
            ind = s[s.rfind('\n', 0, p) + 1:p]
            edits.append((p, p, ''.join('@%s\n%s' % (k, ind) for k in sorted(ks))))
        for a, b, r in sorted(edits, key=lambda e: (e[0], e[1]), reverse=True):
            s = s[:a] + r + s[b:]
        for k in sorted({k for v in annots.values() for k in v}):
            if not re.search(r'^import lombok\.%s;' % k, s, re.M):
                s = add_imports(s, ['import lombok.%s;' % k])
        return s

    def constructors(path, s):
        mask = code_mask(s)
        own = owners(s, mask)
        edits, used = [], set()

        def lead(m):
            return m.start() + len(m.group(0)) - len(m.group(0).lstrip())
        for c in CONSTRUCTOR.finditer(s):
            i = lead(c)
            if not mask[i]:
                continue
            ann, mods, name, params, body = c.groups()
            ps = [(re.sub(r'\s+', '', ' '.join(p.split()[:-1])), p.split()[-1]) for p in params.split(',') if p.strip()]
            asg = re.findall(r'this\s*\.\s*(\w+)\s*=\s*(\w+)', body)
            if not ps or [a for a, _ in asg] != [p for _, p in ps] or any(a != b for a, b in asg):
                continue
            anns = re.findall(r'@(\w+)', ann or '')
            if any(a != 'Inject' for a in anns):
                continue
            brace = own[i]
            hdr = None
            for h in re.finditer(r'\b(class|enum)\s+%s\b' % name, s[:brace]):
                if mask[h.start()]:
                    hdr = h
            if not hdr or own[hdr.start()] != own[brace]:
                continue
            flds = []
            for d in FIELD_DECL.finditer(s):
                p = lead(d)
                if mask[p] and own[p] == brace and d.group(2) not in ('return', 'new', 'throw', 'else'):
                    flds.append((d.group(1), re.sub(r'\s+', '', d.group(2)), d.group(3), d.group(4)))
            req = [(t, n) for mo, t, n, e in flds if 'final' in mo and 'static' not in mo and e == ';']
            alla = [(t, n) for mo, t, n, e in flds if 'static' not in mo and not ('final' in mo and e == '=')]
            kind = 'RequiredArgsConstructor' if req == ps else 'AllArgsConstructor' if alla == ps else None
            if not kind:
                continue
            j = c.start()
            k = s.rfind('\n', 0, j - 1) + 1
            if s[k:j].strip().endswith('*/'):
                cm = s.rfind('/*', 0, j)
                if cm >= 0 and s[s.rfind('\n', 0, cm) + 1:cm].strip() == '':
                    j = s.rfind('\n', 0, cm) + 1
            if s[s.rfind('\n', 0, j - 1) + 1:j].strip() == '':
                j = s.rfind('\n', 0, j - 1) + 1
            edits.append((j, c.end(), ''))
            ls = s.rfind('\n', 0, hdr.start()) + 1
            ind = re.match(r'[ \t]*', s[ls:]).group(0)
            a = '@%s%s' % (kind, '(onConstructor_ = @Inject)' if 'Inject' in anns else '')
            edits.append((ls + len(ind), ls + len(ind), a + '\n' + ind))
            used.add(kind)
        if not edits:
            return s
        for a, b, r in sorted(edits, key=lambda e: (e[0], e[1]), reverse=True):
            s = s[:a] + r + s[b:]
        for k in sorted(used):
            if not re.search(r'^import lombok\.%s;' % k, s, re.M):
                s = add_imports(s, ['import lombok.%s;' % k])
        return s
    return '%d files with accessors, %d with constructors' % (
        rewrite_files(out, accessors), rewrite_files(out, constructors))


BRACE_OPENERS = ('if', 'for', 'while', 'do', 'try', 'switch', 'synchronized', 'else', 'class', 'final')
BRACE_DECL = re.compile(r'^\s*[\w.]+(?:<[^;=()]*>)?(?:\[\s*\])*\s+[A-Za-z_]\w*\s*(=|;)')


def rw_braces(out):
    """The braces around an if/for/while/else body of one statement go, when that body holds
    no '{' (no nested block, lambda block or anonymous class) and is not a declaration or a
    statement that opens another (so no dangling else can arise); lines left holding only
    the brace go. Compiled classes: identical."""
    def edit(path, s):
        m = code_mask(s)
        pair, st = {}, []
        for i, c in enumerate(s):
            if not m[i]:
                continue
            if c in '({':
                st.append(i)
            elif c in ')}' and st:
                pair[st.pop()] = i
        rpair = {v: k for k, v in pair.items()}
        drop = set()
        for o, e in pair.items():
            if s[o] != '{':
                continue
            q = o - 1
            while q >= 0 and (s[q].isspace() or not m[q]):
                q -= 1
            if q < 0:
                continue
            if s[q] == ')':
                p = rpair.get(q)
                if p is None:
                    continue
                r = p - 1
                while r >= 0 and s[r].isspace():
                    r -= 1
                kw = re.search(r'(\w+)$', s[max(0, r - 20):r + 1])
                if not kw or kw.group(1) not in ('if', 'for', 'while'):
                    continue
            elif s[max(0, q - 3):q + 1] != 'else' or (q >= 4 and (s[q - 4].isalnum() or s[q - 4] == '_')):
                continue
            body = ''.join(c if m[o + 1 + k] else ' ' for k, c in enumerate(s[o + 1:e]))
            if '{' in body or body.count(';') != 1 or not body.rstrip().endswith(';'):
                continue
            first = re.match(r'\s*(\w+)', body)
            if not first or first.group(1) in BRACE_OPENERS:
                continue
            if BRACE_DECL.match(body) and first.group(1) not in ('return', 'throw', 'new', 'yield'):
                continue
            drop.add(o)
            drop.add(e)
        if not drop:
            return s
        parts, i = [], 0
        for d in sorted(drop):
            parts.append(s[i:d])
            i = d + 1
        parts.append(s[i:])
        lines = ''.join(parts).split('\n')
        gone, pos = set(), 0
        for li, line in enumerate(s.split('\n')):
            if line.strip() in ('{', '}') and any(pos <= d < pos + len(line) for d in drop):
                gone.add(li)
            pos += len(line) + 1
        return '\n'.join(line for li, line in enumerate(lines) if li not in gone)
    return '%d files' % rewrite_files(out, edit)


IMPORT_ANY = re.compile(r'^import (static )?([\w.]+)\.(\w+|\*);[ \t]*\r?\n', re.M)
# Simple names more than one package this plugin imports from exports (the JDK and the
# RuneLite API): an import of one of these stays single-type, so it keeps winning.
WILDCARD_CLASH = {
    'List', 'Point', 'Timer', 'Menu', 'MenuItem', 'Component', 'Event', 'Rectangle', 'Polygon',
    'Shape', 'Color', 'Font', 'Image', 'Label', 'Panel', 'Window', 'Dialog', 'Path', 'Files',
    'Filter', 'Map', 'Set', 'Entry', 'Type', 'Scene', 'Model', 'Renderable', 'Player', 'Actor',
    'Skill', 'Item', 'Tile', 'Node', 'Constants', 'Config', 'Overlay', 'Layer', 'Objects',
    'Action', 'Border', 'Box', 'Icon', 'Option', 'Optional', 'Executor', 'Callable', 'Future',
    'Function', 'Supplier', 'Consumer', 'Predicate', 'Collectors', 'Stream', 'Random', 'Logger',
    'Name', 'Deque', 'Text', 'Document', 'Element', 'Attribute', 'Kind', 'Mode', 'State', 'Status'}


def project_types(out, top=MAIN):
    """Simple names of every class, interface and enum declared under top."""
    names = set()
    for path in each_java(out, top):
        names |= set(re.findall(r'\b(?:class|interface|enum)\s+([A-Z]\w*)', java_code(read(path))[1]))
    return names


def rw_wildcard(out):
    """Two or more single-type imports from one package become one on-demand import. A
    name in WILDCARD_CLASH, an *ID or *Id class, or a name the plugin declares itself keeps
    its single-type import as well, so javac resolves every name as before; the plugin's
    own packages are left to the flattening. Compiled classes: identical."""
    own = project_types(out)

    def edit(path, s):
        imps = [(m.group(1) or '', m.group(2), m.group(3)) for m in IMPORT_ANY.finditer(s)]
        if not imps:
            return s
        groups = {}
        for st, pkg, name in imps:
            if (name != '*' and not st and not (pkg[:1].isupper() or re.search(r'\.[A-Z]', pkg))
                    and not (pkg + '.').startswith('com.follower.')):
                groups.setdefault(pkg, []).append(name)
        lines, done = [], set()
        for st, pkg, name in imps:
            if not st and len(groups.get(pkg, ())) >= 2:
                if pkg not in done:
                    lines.append('import %s.*;' % pkg)
                    done.add(pkg)
                if name in WILDCARD_CLASH or name.endswith(('ID', 'Id')) or name in own:
                    lines.append('import %s.%s;' % (pkg, name))
            else:
                lines.append('import %s%s.%s;' % (st, pkg, name))
        first = IMPORT_ANY.search(s)
        body = IMPORT_ANY.sub('', s)
        return body[:first.start()] + '\n'.join(lines) + '\n' + body[first.start():]
    return '%d files' % rewrite_files(out, edit)


def rw_flatten(out):
    """Every class moves to com.follower, main and test. Imports of the plugin's own
    types go; nested-type and static imports are re-pointed; a qualified com.follower
    name in code becomes the simple one. In the tests, a class whose simple name another
    test class has takes its old sub-package as a prefix (speech/RunningBitOrderTest:
    SpeechRunningBitOrderTest), so does a class one level further down (speech/before/
    RuleLoader: BeforeRuleLoader), and class names, logger names and source paths in
    their strings follow the move. Compiled classes: identical once com.follower.<sub>
    reads com.follower, which is all that changes in the classes' own names, their
    loggers' names and javac's $SwitchMap$ fields."""
    base = {side: os.path.join(out, side, 'com', 'follower') for side in ('src/main/java', 'src/test/java')}
    subs = sorted({d for side in base.values() if os.path.isdir(side)
                   for d in os.listdir(side) if os.path.isdir(os.path.join(side, d))})
    if not subs:
        return 'nothing to flatten'
    sub_re = '|'.join(map(re.escape, subs))
    # Every file's package path below com.follower, and its simple name in the flat package.
    moves = []      # (path, side, package path, old name, new name)
    for side, root in base.items():
        found = [p for p in each_java(out, side + '/com/follower')]
        names = {}
        for p in found:
            names.setdefault(os.path.basename(p), []).append(p)
        for p in found:
            rel = os.path.relpath(os.path.dirname(p), root).replace(os.sep, '/')
            pkgpath = '' if rel == '.' else rel.replace('/', '.')
            old = os.path.basename(p)[:-5]
            new = old
            if '.' in pkgpath or (pkgpath and len(names[os.path.basename(p)]) > 1):
                if side == 'src/main/java':
                    raise RewriteError('flatten: main class %s.%s would clash' % (pkgpath, old))
                new = pkgpath.rsplit('.', 1)[-1].capitalize() + old
            moves.append((p, side, pkgpath, old, new))
    renamed = {('com.follower.' + pkgpath + '.' + old).replace('..', '.'): new
               for _, _, pkgpath, old, new in moves if old != new}
    nested = {pkgpath.rsplit('.', 1)[-1] for _, _, pkgpath, _, _ in moves if '.' in pkgpath}
    flat_names = [new for _, _, _, _, new in moves]
    if len(flat_names) != len(set(flat_names)):
        raise RewriteError('flatten: two classes would share a name: %s'
                           % ', '.join(sorted({n for n in flat_names if flat_names.count(n) > 1})))
    qualified = re.compile(r'(?<![\w.])com\s*\.\s*follower\s*\.\s*((?:[a-z]\w*\s*\.\s*)*)([A-Z]\w*)')

    def simple_for(pkgpath, name):
        full = 'com.follower.' + (pkgpath + '.' if pkgpath else '') + name
        return renamed.get(full, name)

    for p, side, pkgpath, old, new in moves:
        s = read(p)
        mask = code_mask(s)
        imports = {m.group(2): m.group(1) for m in re.finditer(r'^import\s+([\w.]+\.(\w+))\s*;', s, re.M)}
        declared = set(re.findall(r'\b(?:class|interface|enum)\s+([A-Z]\w*)', java_code(s)[1]))
        edits = []
        # The file's own name, and the names of the classes of its old package that
        # are renamed (a single-type import of another class by that name wins over them).
        if pkgpath:
            local = {o: n for _, s2, pp, o, n in moves if s2 == side and pp == pkgpath and o != n}
            for o, n in local.items():
                if o in imports and imports[o] != 'com.follower.%s.%s' % (pkgpath, o):
                    continue
                for m in re.finditer(r'(?<![\w.$])%s\b' % re.escape(o), s):
                    if mask[m.start()] and not re.match(r'\s*(import|package)\b', s[s.rfind('\n', 0, m.start()) + 1:m.start()]):
                        edits.append((m.start(), m.end(), n))
        for m in qualified.finditer(s):
            if not mask[m.start()]:
                continue
            if re.match(r'\s*(import|package)\b', s[s.rfind('\n', 0, m.start()) + 1:m.start()]):
                continue
            pp = re.sub(r'\s+', '', m.group(1)).rstrip('.')
            name = simple_for(pp, m.group(2))
            clash = (name in imports and not imports[name].startswith('com.follower.')) or \
                (name in declared and name != new)
            edits.append((m.start(), m.end(), 'com.follower.' + name if clash else name))
        for a, b, r in sorted(edits, reverse=True):
            s = s[:a] + r + s[b:]

        def imp(m):
            static, path = m.group(1) or '', re.sub(r'\s+', '', m.group(2))
            parts = path.split('.')
            i = next((k for k, x in enumerate(parts) if x[:1].isupper() or x == '*'), len(parts))
            if parts[i:] in ([], ['*']) and not static:
                return ''                      # the package itself, now this one
            top, rest = simple_for('.'.join(parts[:i]), parts[i]), parts[i + 1:]
            if not static and not rest:
                return ''                      # a class of this package now
            return 'import %scom.follower.%s;\n' % (static, '.'.join([top] + rest))
        s = re.sub(r'(?m)^import\s+(static\s+)?com\.follower\.([\w.*]+)\s*;[ \t]*\r?\n', imp, s)
        s = re.sub(r'(?m)^package\s+com\.follower(\.[\w.]+)?\s*;', 'package com.follower;', s)
        if side == 'src/test/java':
            # Class names, the old packages' loggers and source paths, in the tests' strings.
            def lit(m):
                t = m.group(0)
                t = re.sub(r'"com\.follower\.(?:%s)\.' % sub_re, '"com.follower.', t)
                t = re.sub(r'"com\.follower\.(?:%s)"' % sub_re, '"com.follower"', t)
                for n in nested:   # a further package became a class name prefix
                    t = t.replace('.%s.' % n, '.' + n.capitalize())
                return re.sub(r'src/main/java/com/follower/(?:%s)/' % sub_re, 'src/main/java/com/follower/', t)
            s = re.sub(r'"(?:[^"\\\n]|\\.)*"', lit, s)
        dest = os.path.join(base[side], new + '.java')
        os.remove(p)
        write(dest, s)
    for side, root in base.items():
        for d in subs:
            if os.path.isdir(os.path.join(root, d)):
                shutil.rmtree(os.path.join(root, d))
    return '%d files, %d renamed' % (len(moves), len(renamed))


class RewriteError(Exception):
    pass


def config_types(out):
    """Simple names in the return and parameter types of FollowerConfig's methods."""
    s = read(os.path.join(out, JAVA, 'FollowerConfig.java'))
    b = blank(s)
    k = kinds(b)
    found = set()
    for m in re.finditer(r'(?<![\w.])([\w.<>\[\], ?]+?)\s+(\w+)\s*\(([^()]*)\)', b):
        if k[m.start()] != 'class' or m.group(1).strip() in ('new', 'return'):
            continue
        found |= set(re.findall(r'[A-Z]\w*', m.group(1) + ' ' + re.sub(r'\b\w+\s*(,|$)', r'\1', m.group(3))))
    return found


def rw_public(out):
    """'public' goes wherever one package no longer needs it, in the main sources. It stays
    on FollowerPlugin and FollowerConfig, on every type a FollowerConfig method names
    (RuneLite reads the settings through a java.lang.reflect.Proxy, which the JDK puts in
    a package of its own: the proxy cannot cast to a type it cannot see and throws
    IllegalAccessError, which ProxyCheckTest catches and javac does not), and on @Override
    methods (javac refuses an override with weaker access). Compiled classes: the same
    members, access flags only, but for the bridge javac writes into a public class for a
    public method it inherits from a package-private one, which goes with the need."""
    keep = {'FollowerPlugin', 'FollowerConfig'} | (config_types(out) & project_types(out))

    def edit(path, s):
        b = blank(s)
        cuts = []
        for m in re.finditer(r'\bpublic\b[ \t]*', b):
            t = re.match(r'(?:(?:final|abstract|static)\s+)*(?:class|interface|enum|@interface)\s+(\w+)', b[m.end():m.end() + 120])
            if t and t.group(1) in keep:
                continue
            if re.search(r'@Override\s*$', b[max(0, m.start() - 40):m.start()]):
                continue
            cuts.append((m.start(), m.end()))
        for a, e in reversed(cuts):
            s = s[:a] + s[e:]
        return s
    return '%d files; kept on %s' % (rewrite_files(out, edit), ', '.join(sorted(keep)))


STATIC_SETS = {
    # class: (import target, which members: 'const' UPPER_CASE fields, 'call' methods, 'both')
    'base': {'Math': ('java.lang.Math', 'both'), 'ColorScheme': ('net.runelite.client.ui.ColorScheme', 'const'),
             'Collections': ('java.util.Collections', 'call'), 'KitType': ('net.runelite.api.kit.KitType', 'const')},
    'more': {'BorderLayout': ('java.awt.BorderLayout', 'const'), 'Locale': ('java.util.Locale', 'const'),
             'Strings': ('com.google.common.base.Strings', 'call'),
             'BorderFactory': ('javax.swing.BorderFactory', 'call'),
             'FontManager': ('net.runelite.client.ui.FontManager', 'call'),
             'Files': ('java.nio.file.Files', 'call'), 'System': ('java.lang.System', 'call'),
             'StandardCharsets': ('java.nio.charset.StandardCharsets', 'const'),
             'SwingUtilities': ('javax.swing.SwingUtilities', 'call'),
             'JOptionPane': ('javax.swing.JOptionPane', 'both'),
             'CollisionDataFlag': ('net.runelite.api.CollisionDataFlag', 'const'),
             'FollowerConfig': ('com.follower.FollowerConfig', 'const'),
             'KeepingCompany': ('com.follower.KeepingCompany', 'const'),
             'ErrandController': ('com.follower.ErrandController', 'const')},
}
# SwingUtilities' static import brings SwingConstants' CENTER, EAST... too, which would
# clash with BorderLayout's there.
STATIC_SKIP = {('FollowerPanel.java', 'BorderLayout')}


def class_members(out):
    """({class simple name: identifiers declared directly in its body (fields, methods,
    nested types)}, {class simple name: simple names it extends}) over the main sources."""
    members, supers = {}, {}
    for path in each_java(out):
        b = blank(read(path))
        own = owners(b, [True] * len(b))
        found = {}
        for m in re.finditer(r'\b([A-Za-z_]\w*)\s*(?=[=;,(])', b):
            found.setdefault(own[m.start()], set()).add(m.group(1))
        for kind, name, sup, o, _ in type_bodies(b):
            if name:
                members.setdefault(name, set()).update(found.get(o, set()))
                if sup:
                    supers.setdefault(name, set()).add(sup)
    return members, supers


def rw_statics(out):
    """A class qualified often enough in a file to pay for 'import static X.*;' loses the
    qualifier there: Math, ColorScheme and KitType constants, Collections calls and the
    plugin's own enum constants (first pass), then the classes of STATIC_SETS['more']
    (second pass), each where it gains 10 or more. A file where a rewritten member's name
    also means something else (a field, local, parameter or method of its own, or a member
    of a plugin class it extends) is left alone, and a private nested enum that is imported this way becomes package-private.
    Compiled classes: identical."""
    enums = {}
    for path in each_java(out):
        b = blank(read(path))
        pkg = re.search(r'^package\s+([\w.]+);', b, re.M).group(1)
        top = os.path.basename(path)[:-5]
        for m in re.finditer(r'\benum\s+(\w+)', b):
            name = m.group(1)
            enums.setdefault(name, set()).add(pkg + '.' + (name if name == top else top + '.' + name))
    members, supers = class_members(out)

    def inherited(b):
        # what the classes of this file inherit from the plugin's own classes
        names, todo, seen = set(), [sup for _, _, sup, _, _ in type_bodies(b) if sup], set()
        while todo:
            c = todo.pop()
            if c in seen:
                continue
            seen.add(c)
            names |= members.get(c, set())
            todo += list(supers.get(c, ()))
        return names
    n = 0
    for pass_ in ('base', 'more'):
        table = STATIC_SETS[pass_]

        def edit(path, s):
            b = blank(s)
            fname = os.path.basename(path)
            imports = {m.group(1).rsplit('.', 1)[1]: m.group(1) for m in re.finditer(r'^import\s+([\w.]+)\s*;', s, re.M)}
            pkg = re.search(r'^package\s+([\w.]+);', s, re.M).group(1)
            top = fname[:-5]
            lines_ok = [not re.match(r'\s*(import|package)\b', l) for l in b.split('\n')]
            uses = {}
            for m in re.finditer(r'(?<![\w.])([A-Z]\w*)(\s*\.\s*)([A-Za-z_]\w*)\b(\s*\()?', b):
                cls, _, mem, call = m.groups()
                if not lines_ok[b.count('\n', 0, m.start())] or (fname, cls) in STATIC_SKIP:
                    continue
                isconst = bool(re.match(r'[A-Z][A-Z0-9_]*$', mem)) and not call
                if cls in table:
                    if re.match(r'\s*\.\s*[A-Z]', b[m.end():]):
                        continue
                    mode = table[cls][1]
                    if not ((mode in ('const', 'both') and isconst) or (mode in ('call', 'both') and call)):
                        continue
                elif pass_ == 'base' and isconst and cls in enums:
                    if re.match(r'\s*\.\s*[A-Z]', b[m.end():]):
                        continue   # a nested type's path, not a constant
                else:
                    continue
                uses.setdefault(cls, []).append((m.start(1), m.start(3)))
            edits, add, drop = [], [], []
            from_supers = inherited(b)
            for cls, us in uses.items():
                if cls in table and cls not in enums:
                    fq = table[cls][0]
                elif cls in imports:
                    fq = imports[cls]
                else:
                    cands = enums.get(cls, set())
                    pick = [f for f in cands if f.startswith(pkg + '.' + top + '.') or f == pkg + '.' + cls] or \
                        [f for f in cands if f.rsplit('.', 1)[0] == pkg or f.startswith(pkg + '.')]
                    if len(pick) != 1:
                        continue
                    fq = pick[0]
                # A field, local, parameter or method of this file by a rewritten member's
                # name would silently win over the static import: leave the class alone.
                own_body = None
                mb = re.search(r'\benum\s+%s\b[^{]*\{' % cls, b)
                if mb:
                    d, j = 0, mb.end() - 1
                    for j in range(mb.end() - 1, len(b)):
                        if b[j] == '{':
                            d += 1
                        elif b[j] == '}':
                            d -= 1
                            if d == 0:
                                break
                    own_body = (mb.end(), j)
                clash = False
                for nm in {re.match(r'\w+', b[e:]).group(0) for _, e in us}:
                    if nm in from_supers:
                        clash = True
                        break
                    is_call = any(re.match(r'%s\s*\(' % nm, b[e:]) for _, e in us)
                    for dm in re.finditer(r'(?<![\w.])%s\b' % nm, b):
                        if own_body and own_body[0] <= dm.start() <= own_body[1]:
                            continue
                        if any(a <= dm.start() < e + len(nm) + 1 for a, e in us):
                            continue
                        if re.search(r'\bcase\s+$', b[max(0, dm.start() - 12):dm.start()]):
                            continue   # a case label of a switch on that enum
                        other_call = b[dm.end():].lstrip().startswith('(')
                        if is_call and not other_call:
                            continue   # a variable of that name does not hide a method
                        if is_call and other_call:
                            if re.search(r'[\w>\]]\s+%s\s*\([^;{}]*\)\s*(throws[^{;]*)?[{;]' % nm, b):
                                clash = True
                            continue
                        clash = True
                    if clash:
                        break
                if clash:
                    continue
                line = 'import static %s.*;' % fq
                code_lines = ''.join(l + '\n' for l, ok in zip(b.split('\n'), lines_ok) if ok)
                removable = cls in imports and len(re.findall(r'(?<![\w.])%s\b' % cls, code_lines)) == len(us)
                gain = 2 * len(us) - ntok(line) + (ntok('import %s;' % imports[cls]) if removable else 0)
                if gain < 10:
                    continue
                edits += us
                add.append(line)
                if removable:
                    drop.append('import %s;' % imports[cls])
            if not edits:
                return s
            for a, e in sorted(edits, reverse=True):
                s = s[:a] + s[e:]
            for cls in uses:
                s = re.sub(r'(?m)^(\s*)private\s+((?:static\s+)?enum\s+%s\b)' % cls, r'\1\2', s)
            for d in drop:
                s = re.sub(r'(?m)^' + re.escape(d) + r'[ \t]*\r?\n', '', s, count=1)
            return add_imports(s, sorted(add))
        n += rewrite_files(out, edit)
    return '%d file passes' % n


VAR_PRIMITIVES = {'int', 'long', 'short', 'byte', 'char', 'boolean', 'float', 'double', 'var', 'void'}
VAR_KEYWORDS = {'return', 'new', 'throw', 'else', 'case', 'yield', 'assert', 'import', 'package', 'goto', 'instanceof'}
# Calls whose type argument comes from the declared type: with 'var' they would infer another.
VAR_TARGET_TYPED = re.compile(r'\b(emptyList|emptyMap|emptySet|of|asList|copyOf|singletonList|singleton|singletonMap'
                              r'|fromJson|newArrayList|newHashMap|newHashSet|unmodifiable\w+|comparing\w*|toList|toSet'
                              r'|toMap|collect|empty)\s*\(')


def var_type_end(b, i):
    m = re.match(r'[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*', b[i:])
    if not m:
        return -1
    j = i + m.end()
    k = j
    while k < len(b) and b[k] in ' \t':
        k += 1
    if k < len(b) and b[k] == '<':
        d = 0
        while k < len(b):
            if b[k] == '<':
                d += 1
            elif b[k] == '>':
                d -= 1
                if d == 0:
                    break
            elif b[k] in ';{}()=':
                return -1
            k += 1
        j = k + 1
    while True:
        m2 = re.match(r'\s*\[\s*\]', b[j:])
        if not m2:
            return j
        j += m2.end()


def var_init_end(b, i):
    d, k = 0, i
    while k < len(b):
        c = b[k]
        if c in '([{':
            d += 1
        elif c in ')]}':
            d -= 1
            if d < 0:
                return k, False
        elif c in ';,' and d == 0:
            return k, c == ';'
        k += 1
    return k, False


def top_level(b, i, e):
    """b[i:e] with what is inside brackets left out."""
    out, d = [], 0
    for c in b[i:e]:
        if c in '([{':
            if d == 0:
                out.append(c)
            d += 1
        elif c in ')]}':
            d -= 1
            if d == 0:
                out.append(c)
        elif d == 0:
            out.append(c)
    return ''.join(out)


def var_edit(mode):
    def edit(path, s):
        b = blank(s)
        kd = kinds(b)
        edits = []
        for m in re.finditer(r'(?:(?<=[;{}(])|(?<=^))[ \t\r\n]*(?:final\s+)?(?=[A-Za-z_])', b, re.M):
            i = m.end()
            if kd[i] not in ('code', 'paren'):
                continue
            prev = b[:m.start()].rstrip()[-1:] if m.start() else ''
            te = var_type_end(b, i)
            if te < 0:
                continue
            typ = b[i:te]
            if re.match(r'\w+', typ).group(0) in VAR_PRIMITIVES | VAR_KEYWORDS:
                continue
            nm = re.match(r'\s+([A-Za-z_]\w*)\s*(=(?!=)|:(?!:))', b[te:])
            if not nm or nm.group(1) in VAR_KEYWORDS:
                continue
            if mode == 'generic' and ntok(typ) <= 1:
                continue
            if mode == 'all' and re.sub(r'\s', '', typ) in ('String', 'Object'):
                continue
            after = te + nm.end()
            if nm.group(2) == ':':
                if not re.search(r'\bfor\s*\($', b[max(0, m.start() - 12):m.start()]):
                    continue   # only a for-each variable
            else:
                if prev == '(' and not re.search(r'\btry\s*\($', b[max(0, m.start() - 12):m.start()]):
                    continue   # a cast or an argument, not a try resource
                e, clean = var_init_end(b, after)
                if not clean:
                    continue
                init = b[after:e].strip()
                tl = top_level(b, after, e)
                if not init or init == 'null' or init.startswith('{') or '->' in tl or '::' in tl:
                    continue
                if re.search(r'<\s*>', tl) or VAR_TARGET_TYPED.search(init):
                    continue
                if '?' in tl and ('<' in typ or '[' in typ):
                    continue   # a conditional takes the common supertype, not the declared one
                if re.search(r'\bnew\s+[\w.<>, ?\[\]]+\s*\([^;]*\)\s*\{', init):
                    continue   # an anonymous class: var would type it as the anonymous class
                if not same_type_init(typ, init):
                    continue
            edits.append((i, te))
        for i, te in reversed(edits):
            s = s[:i] + 'var' + s[te:]
        return s
    return edit


def same_type_init(typ, init):
    """False for the initialisers known to have a narrower static type than the declared
    one, where var would change which class the calls name."""
    made = re.match(r'new\s+([\w.]+)', init)
    if made and made.group(1).split('.')[-1] != re.match(r'[\w.]+', typ.strip()).group(0).split('.')[-1]:
        return False   # a subclass made into a variable of its supertype
    return not (re.match(r'(?:Set|Collection)\b', typ.strip()) and re.match(r'(?:[\w.]*\.)?EnumSet\s*\.', init))


def rw_var(out):
    """'var' for a local (for-each and try-with-resources included) whose declared type is
    generic, an array or dotted, when the initialiser has that same type: never for a
    primitive, a diamond, a lambda or method reference, null, an array initialiser, an
    anonymous class or a call whose type argument comes from the declaration.
    Compiled classes: identical."""
    return '%d files' % rewrite_files(out, var_edit('generic'))


def rw_class_lombok(out):
    """Where a class (that extends nothing) has three or more field-level @Getter (or
    @Setter) on instance fields, one on the class replaces them. Lombok never replaces a
    method that exists and class-level @Setter skips final fields. Compiled classes: the
    same, plus the accessors of the class's other instance fields."""
    def edit(path, s):
        b = blank(s)
        bodies, st = [], []
        for i, c in enumerate(b):
            if c == '{':
                st.append(i)
            elif c == '}' and st:
                o = st.pop()
                hs = max(b.rfind(';', 0, o), b.rfind('}', 0, o), b.rfind('{', 0, o)) + 1
                head = b[hs:o]
                if re.search(r'\b(class|enum)\s+\w+', head) and not re.search(r'\bnew\b|\bextends\b', head):
                    bodies.append((o, i, hs))
        edits = []
        for o, cl, hs in bodies:
            found = {'Getter': [], 'Setter': []}
            d, i = 0, o + 1
            while i < cl:
                c = b[i]
                if c == '{':
                    d += 1
                elif c == '}':
                    d -= 1
                elif c == '@' and d == 0:
                    m = re.match(r'@(Getter|Setter)\b(?!\s*\()', b[i:])
                    if m:
                        decl = re.match(r'(\s*@\w+(?:\([^)]*\))?)*\s*((?:\w+\s+)*)', b[i + m.end():])
                        if 'static' not in (decl.group(2) if decl else '').split():
                            found[m.group(1)].append((i, i + m.end()))
                i += 1
            head = b[hs:o]
            hm = list(re.finditer(r'\b(class|enum)\s+\w+', head))[-1]
            line_start = s.rfind('\n', 0, hs + hm.start()) + 1
            indent = re.match(r'[ \t]*', s[line_start:]).group(0)
            for ann in ('Getter', 'Setter'):
                if len(found[ann]) >= 3 and not re.search(r'@%s\b' % ann, head):
                    for a, e in found[ann]:
                        ls = s.rfind('\n', 0, a) + 1
                        k = e
                        while k < len(s) and s[k] in ' \t':
                            k += 1
                        if s[ls:a].strip() == '' and s[k:k + 1] in ('\r', '\n'):
                            edits.append((ls, s.find('\n', k) + 1, ''))
                        else:
                            edits.append((a, k, ''))
                    edits.append((line_start, line_start, indent + '@' + ann + '\n'))
        if not edits:
            return s
        for a, e, t in sorted(edits, key=lambda x: (x[0], x[1]), reverse=True):
            s = s[:a] + t + s[e:]
        if 'import lombok.Setter;' not in s and '@Setter' in s:
            s = s.replace('import lombok.Getter;', 'import lombok.Getter;\nimport lombok.Setter;')
        return s
    return '%d files' % rewrite_files(out, edit)


GUAVA_X = r'([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*(?:\s*\(\s*\))?)*)'
GUAVA_L1 = r'(?<=[(,=?:])\s*|(?<=\breturn)\s+'
GUAVA_L2 = GUAVA_L1 + r'|(?<=\|\|)\s*'
GUAVA_L3 = GUAVA_L2 + r'|(?<=&&)\s*'
GUAVA_RULES = [
    re.compile(r'(?:%s)%s\s*==\s*null\s*\?\s*""\s*:\s*\1(?=\s*[),;:])' % (GUAVA_L1, GUAVA_X)),
    re.compile(r'(?:%s)%s\s*==\s*null\s*\|\|\s*\1\s*\.\s*isEmpty\s*\(\s*\)(?=\s*[),;:?]|\s*\|\|)' % (GUAVA_L2, GUAVA_X)),
    re.compile(r'(?:%s)%s\s*!=\s*null\s*&&\s*!\s*\1\s*\.\s*isEmpty\s*\(\s*\)(?=\s*[),;:?]|\s*&&|\s*\|\|)'
               % (GUAVA_L3, GUAVA_X)),
]


def rw_guava(out):
    """x == null ? "" : x becomes Strings.nullToEmpty(x), x == null || x.isEmpty() becomes
    Strings.isNullOrEmpty(x) and x != null && !x.isEmpty() its negation (Guava ships with
    RuneLite), for a String-typed name, field path or no-argument getter chain, delimited on
    both sides so it cannot bind into a neighbour, in files where it pays for the import.
    The expression is read once instead of twice; every site is a plain read. Compiled
    classes: those calls in place of the comparisons."""
    types, file_types = {}, {}
    for q in each_java(out):
        bq = blank(read(q))
        ft = file_types.setdefault(q, {})
        for d in re.finditer(r'(?<![\w.])([A-Z][\w.]*(?:<[^;(){}=]*>)?(?:\[\])*|int|long|boolean|var)\s+(\w+)\s*(?=[=;,):])', bq):
            t = re.sub(r'\s', '', d.group(1))
            types.setdefault(d.group(2), set()).add(t)
            ft.setdefault(d.group(2), set()).add(t)
        for d in re.finditer(r'(?<![\w.])([A-Z][\w.]*(?:<[^;(){}=]*>)?(?:\[\])*)\s+(\w+)\s*\(\s*\)\s*\{', bq):
            types.setdefault(d.group(2) + '()', set()).add(re.sub(r'\s', '', d.group(1)))

    def is_string(x, ft):
        last = re.sub(r'\s', '', x).split('.')[-1]
        if last.endswith('()'):
            t = ft.get(last) or types.get(last)
            if not t and re.match(r'(get|is)[A-Z]', last):
                f = re.sub(r'^(get|is)', '', last[:-2])
                t = ft.get(f[:1].lower() + f[1:]) or types.get(f[:1].lower() + f[1:])
        else:
            t = (ft.get(last) if '.' not in x else types.get(last)) or types.get(last)
        return bool(t) and t == {'String'}

    def edit(path, s):
        b = blank(s)
        edits = []
        for rx in GUAVA_RULES:
            for m in rx.finditer(b):
                if is_string(m.group(1), file_types.get(path, {})):
                    lead = len(m.group(0)) - len(m.group(0).lstrip())
                    edits.append((m.start() + lead, m.end(), m.group(1)))
        if not edits:
            return s
        parts, last = [], 0
        for a, e, x in sorted(edits):
            if a < last:
                continue
            txt = b[a:e]
            x = re.sub(r'\s+', '', x)
            rep = ('Strings.nullToEmpty(%s)' if '""' in txt else
                   '!Strings.isNullOrEmpty(%s)' if '!=' in txt else 'Strings.isNullOrEmpty(%s)') % x
            parts.append(s[last:a] + rep)
            last = e
        parts.append(s[last:])
        s2 = ''.join(parts)
        if 'import com.google.common.base.Strings;' not in s2:
            m = list(re.finditer(r'(?m)^import\s[^\n]*\n', s2))
            at = m[0].start() if m else re.search(r'(?m)^package[^\n]*\n', s2).end()
            s2 = s2[:at] + 'import com.google.common.base.Strings;\n' + s2[at:]
        if ntok(blank(s2).replace('"', '')) >= ntok(blank(s).replace('"', '')):
            return s
        return s2
    return '%d files' % rewrite_files(out, edit)


def sub_code(pattern, repl, s):
    """re.sub, but only of matches that start in code."""
    mask = code_mask(s)

    def one(m):
        if not mask[m.start()]:
            return m.group(0)
        return m.expand(repl) if isinstance(repl, str) else repl(m)
    return re.sub(pattern, one, s)


def first_import(s, line):
    if line in s:
        return s
    m = list(re.finditer(r'(?m)^import\s[^\n]*\n', s))
    at = m[0].start() if m else re.search(r'(?m)^package[^\n]*\n', s).end()
    return s[:at] + line + '\n' + s[at:]


def rw_micro(out):
    """Small idioms: (a, b) -> Integer.compare(f(a), f(b)) becomes
    Comparator.comparingInt(a -> f(a)) (defined as exactly that comparison); a private
    contains(int[], int) loop becomes Guava's Ints.contains (the same linear scan);
    'final' on a class goes (a flag; nothing extends them), unless Lombok writes its
    equals (which then asks canEqual); a static-only class's empty
    private constructor goes; max(abs(a.getX() - b.getX()), abs(a.getY() - b.getY())) becomes
    a.distanceTo2D(b) (RuneLite's own Chebyshev distance, plane ignored likewise); @Slf4j
    on a class that logs nothing goes. Compiled classes: those calls in place of the
    lambdas, loop and arithmetic, class flags, the removed constructors and loggers."""
    def edit(path, s):
        def cmp(m):
            a, bb, ea, eb = m.groups()
            if re.sub(r'\b%s\b' % a, bb, ea) != eb or re.search(r'\b%s\b' % bb, ea):
                return m.group(0)
            return 'Comparator.comparingInt(%s -> %s)' % (a, ea)
        s2 = sub_code(r'\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*->\s*Integer\s*\.\s*compare\s*\(\s*([^,()]*(?:\([^()]*\))?[^,()]*)'
                    r'\s*,\s*([^,()]*(?:\([^()]*\))?[^,()]*)\s*\)', cmp, s)
        if s2 != s:
            s = s2
            if not re.search(r'import java\.util\.(Comparator|\*);', s):
                s = first_import(s, 'import java.util.Comparator;')
        m = re.search(r'\n\tprivate static boolean contains\(int\[\] (\w+), int (\w+)\)\s*\{\s*for \(int (\w+) : \1\)\s*\{?'
                      r'\s*if \(\3 == \2\)\s*return true;\s*\}?\s*return false;\s*\}\r?\n', s)
        if m:
            s = s[:m.start()] + '\n' + s[m.end():]
            s = sub_code(r'(?<![\w.])contains\(', 'Ints.contains(', s)
            s = first_import(s, 'import com.google.common.primitives.Ints;')
        if not re.search(r'@(EqualsAndHashCode|Data|Value)\b', s):
            # (Lombok's equals asks canEqual unless the class is final)
            s = sub_code(r'\bfinal\s+((?:static\s+)?class\s)', r'\1', s)
            s = sub_code(r'\b(static\s+)final\s+(class\s)', r'\1\2', s)
        s = re.sub(r'\n\tprivate %s\(\)\s*\{\s*\}\r?\n' % os.path.basename(path)[:-5], '\n', s)
        s = sub_code(r'Math\s*\.\s*max\s*\(\s*Math\s*\.\s*abs\s*\(\s*([\w.]+)\s*\.\s*getX\s*\(\s*\)\s*-\s*([\w.]+)\s*\.\s*'
                   r'getX\s*\(\s*\)\s*\)\s*,\s*Math\s*\.\s*abs\s*\(\s*\1\s*\.\s*getY\s*\(\s*\)\s*-\s*\2\s*\.\s*getY\s*'
                   r'\(\s*\)\s*\)\s*\)', r'\1.distanceTo2D(\2)', s)
        if '@Slf4j' in s and not re.search(r'\blog\s*\.', blank(s)):
            s = re.sub(r'(?m)^@Slf4j\s*\n', '', s)
            s = re.sub(r'(?m)^import lombok\.extern\.slf4j\.Slf4j;\s*\n', '', s)
        return s
    return '%d files' % rewrite_files(out, edit)


def string_literals(s):
    """(start, end) of every string literal outside comments."""
    out = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if s.startswith('//', i):
            j = s.find('\n', i)
            i = n if j < 0 else j
        elif s.startswith('/*', i):
            j = s.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c in '"\'':
            j = i + 1
            while j < n and s[j] != c:
                j += 2 if s[j] == BS else 1
            if c == '"':
                out.append((i, j + 1))
            i = j + 1
        else:
            i += 1
    return out


def rw_join_literals(out):
    """"a" + "b" becomes "ab" where two string literals meet in a + chain, unless the right
    one is followed by '.', which would bind tighter. (L + "a") + "b" is L + "ab" for any L,
    and javac folds both alike. Compiled classes: identical."""
    def edit(path, s):
        while True:
            lits = string_literals(s)
            for (a0, a1), (b0, b1) in zip(lits, lits[1:]):
                if s[a1:b0].strip() != '+':
                    continue
                k = b1
                while k < len(s) and s[k] in ' \t\r\n':
                    k += 1
                if k < len(s) and s[k] == '.':
                    continue
                s = s[:a1 - 1] + s[b0 + 1:]
                break
            else:
                return s
    return '%d files' % rewrite_files(out, edit)


CONST_DECL = re.compile(r'^[ \t]*((?:(?:private|public|protected|static|final)[ \t]+)+)'
                        r'(int|long|short|byte|double|float|char|boolean|String)[ \t]+([A-Z][A-Z0-9_]*)[ \t]*=[ \t]*'
                        r'([^;\n]+);[ \t]*\r?\n', re.M)
CONST_LITERAL = re.compile(r'^-?(\d[\d_]*[LlFfDd]?|\d*\.\d+[FfDd]?|0x[0-9a-fA-F_]+[Ll]?|"(?:\\.|[^"\\])*"|true|false|\'.\')$')


def blank_comments(s):
    """s with its comments blanked to spaces (newlines kept); literals kept."""
    m = code_mask(s)
    out = []
    i, n = 0, len(s)
    # code_mask marks literals and comments alike; tell them apart by their first char
    while i < n:
        if not m[i] and (s.startswith('//', i) or s.startswith('/*', i)):
            j = i
            while j < n and not m[j]:
                j += 1
            out.append(''.join(c if c == '\n' else ' ' for c in s[i:j]))
            i = j
        elif not m[i]:
            j = i
            while j < n and not m[j]:
                j += 1
            out.append(s[i:j])
            i = j
        else:
            out.append(s[i])
            i += 1
    return ''.join(out)


def rw_inline_constants(out):
    """A static final constant whose value is a literal, used exactly once in the main
    sources and never named in the tests, is written at its use as the literal with the
    name in a comment: emote(855 /* NOD */). Its declaration goes, and its Javadoc (or the
    line comments over a declaration that stands alone) moves over the line of the use as
    '// NAME: ...'. A constant with an annotation stays. A long, double or float keeps its
    type with a suffix (L, d, f); short, byte and char constants stay; a negative value
    after an operator is parenthesised. javac already writes a compile-time constant's
    value at its use, so the methods compile the same. Compiled classes: identical, but
    for the removed constant fields."""
    paths = list(each_java(out))
    raw = {p: read(p) for p in paths}
    code = {p: blank_comments(s) for p, s in raw.items()}
    nostr = {p: re.sub(r'"(?:\\.|[^"\\\n])*"', lambda m: '"' + ' ' * (len(m.group(0)) - 2) + '"', s)
             for p, s in code.items()}
    tests = '\n'.join(strip_comments(read(p)) for p in each_java(out, 'src/test/java/'))
    cands = []
    for p, s in code.items():
        for m in CONST_DECL.finditer(s):
            mods, typ, name, val = m.group(1), m.group(2), m.group(3), m.group(4).strip()
            if 'static' not in mods or 'final' not in mods or not CONST_LITERAL.match(val) or typ in ('short', 'byte', 'char'):
                continue
            # an annotation on the line above belongs to this declaration
            above = s[:m.start()].rstrip().rsplit('\n', 1)[-1].strip()
            if above.startswith('@') or above.endswith(')') and '@' in above:
                continue
            plain_int = re.match(r'^-?(\d[\d_]*|0x[0-9a-fA-F_]+)$', val)
            if typ == 'long' and plain_int:
                val += 'L'
            elif typ == 'double' and re.match(r'^-?\d[\d_]*$', val):
                val += 'd'
            elif typ == 'float' and re.match(r'^-?(\d[\d_]*|\d*\.\d+)$', val):
                val += 'f'
            elif typ == 'int' and not plain_int:
                continue
            pat = re.compile(r'(?<![\w$.])(?:\w+\s*\.\s*)*' + name + r'(?![\w$])')
            uses = [(q, u.start(), u.end()) for q, t in nostr.items() if name in t for u in pat.finditer(t)]
            uses = [u for u in uses if not (u[0] == p and m.start() <= u[1] < m.end())]
            if len(uses) == 1 and not re.search(r'(?<![\w$])' + name + r'(?![\w$])', tests):
                cands.append((p, m.start(), m.end(), name, val, uses[0]))
    edits = {}
    notes = {}       # path -> {line start of the use: [comment lines]}
    for p, ds, de, name, val, (q, us, ue) in cands:
        s = raw[p]
        start, doc = ds, []
        k = s.rfind('\n', 0, ds - 1) + 1     # the line above the declaration
        above = s[k:ds].strip()
        alone = s[de:s.find('\n', de) + 1].strip() in ('', '}')   # no declaration follows it
        if above.endswith('*/'):
            c = s.rfind('/*', 0, ds)
            if c >= 0 and s[s.rfind('\n', 0, c) + 1:c].strip() == '':
                start = s.rfind('\n', 0, c) + 1
                text = re.sub(r'^/\*\*?|\*/$', '', s[c:ds].strip())
                doc = [re.sub(r'^\s*\*\s?', '', l).strip() for l in text.split('\n')]
        elif above.startswith('//') and alone:
            # line comments over a declaration that stands alone are its own
            while k > 0 and s[s.rfind('\n', 0, k - 1) + 1:k].strip().startswith('//'):
                k = s.rfind('\n', 0, k - 1) + 1
            start = k
            doc = [l.strip()[2:].strip() for l in s[k:ds].split('\n')]
        doc = [l for l in doc if l]
        edits.setdefault(p, []).append((start, de, '\0\n'))
        prev = code[q][:us].rstrip()[-1:]
        lit = '(' + val + ')' if val.startswith('-') and prev in '-+*/%' else val
        edits.setdefault(q, []).append((us, ue, lit + ' /* ' + name + ' */'))
        if doc:
            ls = raw[q].rfind('\n', 0, us) + 1
            notes.setdefault(q, {}).setdefault(ls, []).append(['%s: %s' % (name, doc[0])] + doc[1:])
    for p in set(edits) | set(notes):
        s = raw[p]
        es = list(edits.get(p, []))
        for ls, docs in notes.get(p, {}).items():
            ind = re.match(r'[ \t]*', s[ls:]).group(0)
            es.append((ls, ls, ''.join(ind + '// ' + l + '\n' for d in docs for l in d)))
        for a, b, r in sorted(es, key=lambda e: (e[0], e[1]), reverse=True):
            s = s[:a] + r + s[b:]
        # The removed declarations' lines go; where they leave two blank lines meeting,
        # one of them goes too.
        lines = []
        gap = False
        for line in s.split('\n'):
            if line == '\0':
                gap = True
                continue
            if gap and not line.strip() and lines and not lines[-1].strip():
                gap = False
                continue
            gap = False
            lines.append(line)
        write(p, '\n'.join(lines))
    return '%d constants' % len(cands)


def type_bodies(b):
    """[(kind, name, extended simple name or None, '{' offset, '}' offset)] of every class,
    interface, enum and anonymous class body in blanked code b; an anonymous class has
    kind 'new', no name, and the type it instantiates as its extended name."""
    pair, st = {}, []
    for i, c in enumerate(b):
        if c == '{':
            st.append(i)
        elif c == '}' and st:
            pair[st.pop()] = i
    res = []
    for o, e in sorted(pair.items()):
        head = b[max(b.rfind(';', 0, o), b.rfind('}', 0, o), b.rfind('{', 0, o)) + 1:o]
        m = re.search(r'\b(class|interface|enum)\s+(\w+)(?:\s*<[^{]*?>)?(?:\s+extends\s+([\w.]+))?', head)
        if m:
            res.append((m.group(1), m.group(2), m.group(3) and m.group(3).split('.')[-1], o, e))
            continue
        m = re.search(r'\bnew\s+([\w.]+)\s*(?:<[^{]*?>)?\s*\([^;{}]*\)\s*$', head)
        if m:
            res.append(('new', None, m.group(1).split('.')[-1], o, e))
    return res


METHOD_DECL = re.compile(r'[\w>\]]\s+(\w+)\s*\([^;{}]*\)\s*(?:throws[^{;]*)?\{')


def rw_private(out):
    """'private' goes from every member and nested type of the main sources. It stays in
    interfaces, and on a method whose name a class of the plugin and one of its plugin
    superclasses both declare, which would otherwise start overriding (no instruction
    would change, so only this rule and an override check stand in the way). Compiled
    classes: the same members; access flags only, and no synthetic lambda where a method
    reference reached a private method of another class."""
    bodies = {}        # path -> type_bodies
    declared = {}      # class simple name -> method names declared directly in its body
    for path in each_java(out):
        b = blank(read(path))
        own = owners(b, [True] * len(b))
        bodies[path] = tb = type_bodies(b)
        by_open = {o: (kind, name) for kind, name, _, o, _ in tb}
        names = {}
        for m in METHOD_DECL.finditer(b):
            names.setdefault(own[m.start(1)], set()).add(m.group(1))
        for kind, name, _, o, _ in tb:
            if name:
                declared.setdefault(name, set()).update(names.get(o, set()))
        for kind, name, sup, o, _ in tb:
            if kind == 'new':
                declared.setdefault(('new', path, o), set()).update(names.get(o, set()))
    supers = {}
    for path, tb in bodies.items():
        for kind, name, sup, o, _ in tb:
            if sup:
                supers.setdefault(name or ('new', path, o), set()).add(sup)
    related = set()
    for c in supers:
        seen, todo = set(), list(supers[c])
        while todo:
            a = todo.pop()
            if a in seen or a not in declared:
                continue
            seen.add(a)
            related |= declared.get(c, set()) & declared[a]
            todo += list(supers.get(a, ()))

    def edit(path, s):
        b = blank(s)
        own = owners(b, [True] * len(b))
        interfaces = {o for kind, _, _, o, _ in type_bodies(b) if kind == 'interface'}
        cuts = []
        for m in re.finditer(r'\bprivate\s+', b):
            if own[m.start()] in interfaces:
                continue
            decl = re.match(r'(?:(?:static|final|synchronized|abstract|native)\s+)*(?:<[^>]*>\s*)?'
                            r'[\w.<>\[\], ?]+?\s+(\w+)\s*\(', b[m.end():])
            if decl and decl.group(1) in related:
                continue
            cuts.append((m.start(), m.end()))
        for a, e in reversed(cuts):
            s = s[:a] + s[e:]
        return s
    return '%d files; kept on methods named %s' % (rewrite_files(out, edit), ', '.join(sorted(related)) or 'none')


MERGE_DECL = re.compile(r'^([ \t]*)((?:(?:public|protected|private|static|final|transient|volatile)\s+)*)'
                        r'([A-Za-z_][\w.]*(?:<[^;=(){}]*>)?(?:\[\])*)\s+([A-Za-z_]\w*)\s*(=\s*[^;{}]*)?;[ \t]*(\r?)$')


def merge_edit(want):
    def edit(path, s):
        m = code_mask(s)
        k = kinds(''.join(c if m[i] or c == '\n' else ' ' for i, c in enumerate(s)))
        lines = s.split('\n')
        out_lines, run, pos = [], [], 0
        code = ''.join(c if m[i] else ' ' for i, c in enumerate(s))

        def annotated(at):
            # Whether an annotation ends just before offset at (it would then apply to
            # every variable of a merged declaration): '@X' or '@X(...)', over any lines.
            t = code[:at].rstrip()
            if t.endswith(')'):
                d, j = 0, len(t) - 1
                while j >= 0:
                    d += {')': 1, '(': -1}.get(t[j], 0)
                    if d == 0:
                        break
                    j -= 1
                t = t[:j].rstrip()
            return bool(re.search(r'@\s*[\w.]+$', t))

        def flush():
            if len(run) > 1:
                first = run[0][1]
                ind, cr = first.group(1), first.group(6)
                for j, (idx, mm, key) in enumerate(run):
                    name = mm.group(4) + ((' ' + mm.group(5).strip()) if mm.group(5) else '')
                    end = ',' if j < len(run) - 1 else ';'
                    out_lines.append((ind + mm.group(2) + mm.group(3) + ' ' if j == 0 else ind + '\t') + name + end + cr)
            else:
                out_lines.extend(lines[idx] for idx, _, _ in run)
        for idx, line in enumerate(lines):
            mm = MERGE_DECL.match(line)
            ctx = k[pos + len(line) - len(line.lstrip())] if line.strip() else None
            if mm and ctx == want and mm.group(3) not in ('return', 'throw', 'break', 'continue', 'var', 'package', 'import'):
                key = re.sub(r'\s+', ' ', (mm.group(2) + mm.group(3)).strip())
                if run and run[-1][2] == key and run[-1][0] == idx - 1:
                    run.append((idx, mm, key))
                else:
                    flush()
                    run = []
                    if annotated(pos):
                        out_lines.append(line)
                    else:
                        run = [(idx, mm, key)]
            else:
                flush()
                run = []
                out_lines.append(line)
            pos += len(line) + 1
        flush()
        return '\n'.join(out_lines)
    return edit


def rw_merge_fields(out):
    """Adjacent field declarations with the same modifiers and type, no annotation and no
    comment between them, become one declaration, each field on its own line still:
    'int a = 1;' 'int b = 2;' becomes 'int a = 1,' 'b = 2;'. Field order is kept.
    Compiled classes: identical."""
    return '%d files' % rewrite_files(out, merge_edit('class'))


def rw_merge_locals(out):
    """The same for adjacent local variable declarations. Compiled classes: identical."""
    return '%d files' % rewrite_files(out, merge_edit('code'))


def rw_var_all(out):
    """'var' as in the var rewrite, now for single-name types too (WorldPoint, NPC...),
    never for String or Object. Compiled classes: identical."""
    return '%d files' % rewrite_files(out, var_edit('all'))


REWRITES = {
    'lombok': rw_lombok,
    'fqn': rw_fqn,
    'nested': rw_nested,
    'braces': rw_braces,
    'wildcard': rw_wildcard,
    'flatten': rw_flatten,
    'public': rw_public,
    'statics': rw_statics,
    'var': rw_var,
    'class-lombok': rw_class_lombok,
    'guava': rw_guava,
    'micro': rw_micro,
    'join-literals': rw_join_literals,
    'inline-constants': rw_inline_constants,
    'private': rw_private,
    'merge-fields': rw_merge_fields,
    'merge-locals': rw_merge_locals,
    'var-all': rw_var_all,
}


def main_texts(out):
    """{file name: text} of the tree's main sources."""
    return {os.path.basename(p): read(p) for p in each_java(out)}


# How a rewrite can change a piece of source a test quotes, tried alone and together.
FOLLOW = [
    lambda t: re.sub(r'\bpublic ', '', t),
    lambda t: re.sub(r'\bprivate ', '', t),
    lambda t: re.sub(r'\n[ \t]*[{}][ \t]*(?=\n|$)', '', t),                            # braces
    lambda t: re.sub(r'\b(?:[A-Z]\w*\.)+(?=[A-Z][A-Z0-9_]*\b(?!\s*\())', '', t),      # statics
    lambda t: re.sub(r'\bfor \([\w.<>\[\], ?]+? (\w+ :)', r'for (var \1', t),         # var
]
JAVA_ESCAPES = {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f', '"': '"', "'": "'", BS: BS}


def java_unescape(body):
    return re.sub(r'\\(u+[0-9a-fA-F]{4}|[0-7]{1,3}|.)', lambda m: (
        chr(int(m.group(1).lstrip('u'), 16)) if m.group(1)[0] == 'u' else
        chr(int(m.group(1), 8)) if m.group(1)[0].isdigit() else JAVA_ESCAPES.get(m.group(1), m.group(0))), body)


def java_literal(text):
    return '"' + text.replace(BS, BS + BS).replace('"', BS + '"').replace('\n', BS + 'n') \
        .replace('\t', BS + 't').replace('\r', BS + 'r') + '"'


def follow_tests(out, before):
    """The tests that read the main sources as text quote pieces of them (a method's
    signature, a guard as it is laid out). A quote the rewrites changed - one that a main
    source holding it before them now holds less often, while each such source holds its
    rewritten form as often as it held the quote - is rewritten the same way: without
    'public' or 'private', without
    the braces that went, with an imported constant unqualified, or with a for-each
    variable's 'var'. A quote written as "a" + "b" is read whole. Returns the quotes
    changed, as (file, old, new)."""
    import itertools
    after = main_texts(out)
    changed = []

    def edit(path, s):
        lits = string_literals(s)
        runs, i = [], 0
        while i < len(lits):          # literals joined by + into one quote
            j = i
            while j + 1 < len(lits) and s[lits[j][1]:lits[j + 1][0]].strip() == '+':
                j += 1
            runs.append((lits[i][0], lits[j][1], [lits[k] for k in range(i, j + 1)]))
            i = j + 1
        edits = []
        for a, e, parts in runs:
            quote = ''.join(java_unescape(s[x + 1:y - 1]) for x, y in parts)
            where = {f: t.count(quote) for f, t in before.items() if quote in t}
            if len(quote) < 8 or all(after.get(f, '').count(quote) == n for f, n in where.items()):
                continue
            for k in range(1, len(FOLLOW) + 1):
                found = None
                for combo in itertools.combinations(FOLLOW, k):
                    new = quote
                    for f in combo:
                        new = f(new)
                    if new != quote and len(new) >= 8 and \
                            all(after.get(f, '').count(new) == n for f, n in where.items()):
                        found = new
                        break
                if found:
                    edits.append((a, e, java_literal(found)))
                    changed.append((os.path.basename(path), quote, found))
                    break
        for a, e, r in sorted(edits, reverse=True):
            s = s[:a] + r + s[e:]
        return s
    rewrite_files(out, edit, 'src/test/java/')
    return changed


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
    code = java_code(read(java_file(out, 'Condition.java')))[0]
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
    """The everyday command list's commands (commands.tsv) and the ::follower switch's own case labels."""
    listed = re.findall(r'^([a-z]+)', read(os.path.join(out, RES, 'commands.tsv')), re.M)
    code, mask = java_code(read(os.path.join(out, JAVA, 'FollowerPlugin.java')))
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
    for name in manifest.get('export_rewrites', []):
        if name not in REWRITES:
            sys.exit('make_public: the manifest names export rewrite %s, which this tool does not have' % name)
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

    before = main_texts(out)
    for name in manifest.get('export_rewrites', []):
        try:
            print('  rewrite %s: %s' % (name, REWRITES[name](out)))
        except RewriteError as e:
            sys.exit('make_public: rewrite %s: %s' % (name, e))
    if manifest.get('export_rewrites'):
        followed = follow_tests(out, before)
        print('  tests follow the rewritten source: %d quotes' % len(followed))
        for f, old, new in followed:
            print('    %s: %r -> %r' % (f, old[:70], new[:70]))

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
    condition = read(java_file(out, 'Condition.java'))
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
