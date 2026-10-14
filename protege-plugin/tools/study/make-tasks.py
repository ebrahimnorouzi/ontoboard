"""Builds the user-study task materials from a real workbook, deterministically.

Why a generator rather than committed fixtures: the tasks are supposed to be real curation work
on real data, and the NFDI-MatWerk workbook is not ours to redistribute. So this takes the
workbook, trims two tabs to a size a participant can finish inside twenty minutes, CLEANS what the
trimming and the years of pasting left behind, seeds exactly six defects in each - one per audit
kind - and writes the answer key beside them. Nothing random:
the same workbook gives the same materials, so two participants in the same condition see the
same thing and a second site can reproduce the study.

Run it from protege-plugin:

    python -I tools/study/make-tasks.py "path/to/UniqueIds(8) - cleaned.xlsx" target/study

What it writes

    A/Software.tsv            task set A, 40 rows, six defects
    B/ServiceProduct.tsv      task set B, same shape, six defects in a different arrangement
    pristine/Software.tsv     the trimmed tab before the defects, for scoring
    pristine/ServiceProduct.tsv
    answer-key.tsv            one row per defect: task, sheet, row, column, kind, was, now
    answer-key.md             the same, as a facilitator can read it

The six kinds are the ones the audit reports and can score objectively. They are seeded at fixed
row offsets so that A and B are matched: the same kinds, the same number, at the same distances
from the top, in different columns of different content.

Verify the result rather than trusting it:

    java -cp "target/classes:$(cat target/cp.txt)" \\
         de.fizkarlsruhe.ise.ontoboard.bench.Bench audit target/study/A

All six seeded defects must appear, and nothing else should.
"""
import io
import os
import sys

# One place for the shape of the two task sets, so A and B cannot drift apart.
ROWS = 40

# More rows are trimmed than are used, because some of them cannot be made clean. A name that
# means two things, or two rows for one thing, carries no replacement - the audit refuses to
# guess which of the two to keep, and so should this. Those rows are dropped and the surplus
# makes up the difference.
CANDIDATES = 56

# People and Organization, not the Software and ServiceProduct tabs the evaluation plan named.
# The plan was written before anybody checked the template rows, and those two tabs have no
# label column at all - their "Software name" and "Service name" are `I IAO_0000235` reference
# columns, pointing at a name individual. Three of the six defect kinds need a label to live in,
# so seeding them there produced a task the audit could not score: no near duplicate at all, and
# three unresolved references where one was wanted.
#
# People (60 rows, 10 columns) and Organization (56 rows, 12 columns) both carry ID in column 1,
# `A rdfs:label` in column 3 and a reference column in column 4, which is what makes them a
# matched pair for counterbalancing.
TASKS = [
    {'task': 'A', 'tab': 'People'},
    {'task': 'B', 'tab': 'Organization'},
]

# Row offsets into the trimmed 40, counted from the first data row. Fixed, matched across tasks,
# and spread so that no two defects sit next to each other - a participant who finds one should
# not find the next by looking at the next line.
SEEDS = [
    {'kind': 'DUPLICATE_ID', 'at': 4},
    {'kind': 'MISSING_ID', 'at': 11},
    {'kind': 'UNRESOLVED_REFERENCE', 'at': 17},
    {'kind': 'TYPOGRAPHIC_CHARACTER', 'at': 23},
    {'kind': 'PLACEHOLDER', 'at': 30},
    {'kind': 'NEAR_DUPLICATE', 'at': 36},
]

PLACEHOLDER_TEXT = 'TODO: describe this resource'
CURLY_OPEN = '“'
CURLY_CLOSE = '”'


def read_tabs(workbook):
    """Every tab of the workbook, as rows of strings, using openpyxl."""
    try:
        from openpyxl import load_workbook
    except ImportError:
        raise SystemExit('This needs openpyxl: python -m pip install openpyxl')
    book = load_workbook(workbook, read_only=True, data_only=True)
    tabs = {}
    for name in book.sheetnames:
        rows = []
        for row in book[name].iter_rows(values_only=True):
            rows.append(['' if cell is None else str(cell) for cell in row])
        tabs[name] = rows
    book.close()
    return tabs


# The characters the audit reports as typographic, and what they are standing in for. Named here
# rather than inferred, so that cleaning a tab for a task cannot quietly disagree with what the
# audit will say about it - and the generator checks the result with the audit anyway.
TYPOGRAPHIC = {
    '\u00a0': ' ',      # no-break space
    '\u2018': "'",      # left single quote
    '\u2019': "'",      # right single quote
    '\u201c': '"',      # left double quote
    '\u201d': '"',      # right double quote
    '\u2013': '-',      # en dash
    '\u2014': '-',      # em dash
}


def clean(rows, ids, labels):
    """A trimmed tab with nothing wrong with it, so that six seeded defects are the only six.

    Three things, each because the trimmed tab has it and a task cannot:

      - TYPOGRAPHIC CHARACTERS. The real data is full of them - 128 in the first forty rows of
        Software - because it has been pasted out of web pages and word processors for years.
        Left in, the one seeded curly quote would be the 129th.
      - WHITESPACE. Same reason, and the audit reports it separately.
      - REFERENCES THAT POINT OUT OF THE TRIMMED SET. This one is caused BY the trimming, not
        found in the data: a 40-row tab on its own cannot resolve a reference to a row that was
        cut or to a term in one of the other twenty-seven tabs, so 461 of task A's findings were
        made by the act of making the task. Those cells are blanked, which is a documented
        simplification of the data rather than a repair of it.

    Returns how many cells each of the three changed, for the generator to print.
    """
    changed = {'typographic': 0, 'whitespace': 0, 'references': 0}
    known = set()
    for row in rows[2:]:
        for column in (ids, labels):
            if column and column <= len(row):
                value = row[column - 1].strip()
                if value:
                    known.add(value)

    spec = rows[1]
    for row in rows[2:]:
        for at in range(len(row)):
            before = row[at]
            value = before
            for odd, plain in TYPOGRAPHIC.items():
                if odd in value:
                    value = value.replace(odd, plain)
            if value != before:
                changed['typographic'] += 1
            tidied = ' '.join(value.split())
            if tidied != value:
                changed['whitespace'] += 1
            row[at] = tidied

    for row in rows[2:]:
        for at in range(len(row)):
            said = (spec[at] if at < len(spec) else '').strip()
            if not said.startswith('I ') or at + 1 in (ids, labels):
                continue
            value = row[at].strip()
            if not value:
                continue
            separator = ','
            if 'SPLIT=' in said:
                separator = said.split('SPLIT=', 1)[1].strip() or ','
            parts = [v.strip() for v in value.split(separator)] if separator in value else [value]
            kept = [v for v in parts if v in known]
            if len(kept) != len(parts):
                changed['references'] += 1
                row[at] = separator.join(kept)
    return changed


def label_column(template_row):
    for at, spec in enumerate(template_row, start=1):
        said = (spec or '').strip()
        if said == 'LABEL' or (said.startswith(('A ', 'AL ')) and 'label' in said):
            return at
    return 0


def reference_column(template_row, avoid):
    """A column that names another term, which is what an unresolved reference lives in."""
    for at, spec in enumerate(template_row, start=1):
        said = (spec or '').strip()
        if at != avoid and said.startswith('I '):
            return at
    return 0


def text_column(template_row, avoid):
    """A column holding prose, for the curly quote and the placeholder."""
    best = 0
    for at, spec in enumerate(template_row, start=1):
        said = (spec or '').strip()
        if at in avoid:
            continue
        if said.startswith('A ') and 'label' not in said:
            return at
        if said.startswith('I ') and not best:
            best = at
    return best



def audit(folder, classpath):
    """What the shipped audit says about a folder, as a list of dicts.

    Shelling out to the bench rather than reimplementing the audit in Python, because a
    generator whose idea of "clean" disagrees with the tool's is a generator that produces a
    task nobody can score. This is the same code path the study will be scored with.
    """
    import subprocess
    out = subprocess.check_output(
        ['java', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp', classpath,
         'de.fizkarlsruhe.ise.ontoboard.bench.Bench', 'audit', folder],
        stderr=subprocess.DEVNULL).decode('utf-8')
    rows = [line.split('\t') for line in out.replace('\r\n', '\n').split('\n')
            if line.strip() and not line.startswith('#')]
    if not rows:
        return []
    head = rows[0]
    return [dict(zip(head, r)) for r in rows[1:] if len(r) == len(head)]


def scrub(folder, rows_by_tab, classpath, rounds=8, dropped=None):
    """Edits the trimmed tabs until the audit reports nothing at all.

    The approximate clean above gets the characters and the whitespace, which are in the data.
    It cannot get the references, because trimming forty rows out of a twenty-eight tab workbook
    breaks every reference to the rest of it - 769 of them in the first attempt at this - and
    which columns hold a reference is a question only the template row and the audit can answer.

    So: ask, blank what it names, ask again. A reference is blanked rather than repaired because
    the thing it pointed at is not in the task; everything else takes the audit's own suggestion.
    """
    dropped = {} if dropped is None else dropped
    for attempt in range(rounds):
        findings = audit(folder, classpath)
        if not findings:
            return attempt
        acted = 0
        doomed = {}
        for each in findings:
            tab = each.get('sheet', '')
            rows = rows_by_tab.get(tab)
            if rows is None:
                continue
            row = int(each['row']) - 1
            column = int(each['column']) - 1
            if row < 0 or row >= len(rows):
                continue
            if each['kind'] == 'UNRESOLVED_REFERENCE' and column >= 0:
                while len(rows[row]) <= column:
                    rows[row].append('')
                rows[row][column] = ''
            elif each.get('suggestion', '') and column >= 0:
                while len(rows[row]) <= column:
                    rows[row].append('')
                rows[row][column] = each['suggestion']
            else:
                # No replacement, and none should be invented: two rows for one thing, or a name
                # that means two things, is a question for a curator. The row goes instead, and
                # CANDIDATES is why there are spare ones.
                doomed.setdefault(tab, set()).add(row)
                continue
            acted += 1
        for tab, gone in doomed.items():
            rows = rows_by_tab[tab]
            rows_by_tab[tab][:] = [r for i, r in enumerate(rows) if i < 2 or i not in gone]
            acted += len(gone)
            dropped[tab] = dropped.get(tab, 0) + len(gone)
        for tab, rows in rows_by_tab.items():
            write_tsv(os.path.join(folder, tab + '.tsv'), rows)
        if not acted:
            break
    remaining = audit(folder, classpath)
    if remaining:
        kinds = {}
        for each in remaining:
            kinds[each['kind']] = kinds.get(each['kind'], 0) + 1
        raise SystemExit('could not get the trimmed tabs clean; the audit still reports %s. '
                         'The tasks would not be scoreable.' % kinds)
    return rounds


def trim(rows, want=None):
    """The two header rows plus the first `want` data rows that carry an identifier."""
    want = CANDIDATES if want is None else want
    if len(rows) < 3:
        raise SystemExit('a tab with no data rows cannot be a task')
    kept = [list(rows[0]), list(rows[1])]
    for row in rows[2:]:
        if len(kept) - 2 >= want:
            break
        if row and row[0].strip():
            kept.append(list(row))
    if len(kept) - 2 < ROWS:
        raise SystemExit('only %d usable rows; need %d' % (len(kept) - 2, ROWS))
    width = max(len(r) for r in kept)
    for row in kept:
        while len(row) < width:
            row.append('')
    return kept


def referenced_values(rows):
    """Every value that some row points at, so that altering one can be avoided.

    Changing an identifier or a label breaks every reference to it, and a broken reference is
    its own finding. The first version of this seeded six defects and the audit reported eight,
    because blanking an ID and retyping a label each orphaned a reference somewhere else - so
    the task had two findings nobody had planned and the scoring was meaningless.
    """
    spec = rows[1]
    pointed = set()
    for row in rows[2:]:
        for at in range(len(row)):
            said = (spec[at] if at < len(spec) else '').strip()
            if not said.startswith(('I ', 'AI ')):
                continue
            value = row[at].strip()
            if not value:
                continue
            separator = said.split('SPLIT=', 1)[1].strip() if 'SPLIT=' in said else ''
            for part in (value.split(separator) if separator else [value]):
                if part.strip():
                    pointed.add(part.strip())
    return pointed


def safe_rows(rows, ids, labels):
    """Row indices whose identifier and label nothing else points at."""
    pointed = referenced_values(rows)
    safe = []
    for index in range(2, len(rows)):
        row = rows[index]
        identifier = row[ids - 1].strip() if ids <= len(row) else ''
        label = row[labels - 1].strip() if labels <= len(row) else ''
        if identifier and label and identifier not in pointed and label not in pointed:
            safe.append(index)
    return safe


def seed(rows, tab):
    """Puts the six defects in, and returns what it did.

    Every defect lands on a row nothing points at, starting from the planned offset and walking
    forward to the next safe one, so that A and B stay matched on kind and roughly on position
    without any defect quietly creating a seventh finding.
    """
    spec = rows[1]
    ids = 1
    labels = label_column(spec)
    if not labels:
        raise SystemExit(tab + ' has no label column, so three of the six kinds cannot be '
                         'seeded in it. Pick a tab whose template row has LABEL or A rdfs:label.')
    refs = reference_column(spec, labels)
    if not refs:
        raise SystemExit(tab + ' has no reference column, so an unresolved reference cannot be '
                         'seeded in it.')
    available = safe_rows(rows, ids, labels)
    if len(available) < len(SEEDS) + 2:
        raise SystemExit('only %d rows of %s can be altered without breaking a reference; '
                         'need %d' % (len(available), tab, len(SEEDS) + 2))
    used = set()
    done = []

    for each in SEEDS:
        preferred = 2 + each['at']
        row = next((r for r in available if r >= preferred and r not in used), None)
        if row is None:
            row = next(r for r in available if r not in used)
        used.add(row)
        number = row + 1
        kind = each['kind']
        if kind == 'DUPLICATE_ID':
            column, was = ids, rows[row][ids - 1]
            now = rows[available[0]][ids - 1]
        elif kind == 'MISSING_ID':
            column, was, now = ids, rows[row][ids - 1], ''
        elif kind == 'UNRESOLVED_REFERENCE':
            # A reference that is nearly right, not a cell full of nonsense. The fix is then to
            # correct a name rather than to delete it, which is the work this is meant to
            # measure - the first version blanked the cell and then asked the participant to
            # blank it again.
            row = next((r for r in available
                        if r not in used and len(rows[r][refs - 1].strip()) > 6), row)
            used.add(row)
            number = row + 1
            column, was = refs, rows[row][refs - 1]
            here = was.strip()
            now = (here[:len(here) // 2] + here[len(here) // 2 + 1:] if len(here) > 6
                   else 'Nonexistent Term 99')
        elif kind == 'TYPOGRAPHIC_CHARACTER':
            column, was = labels, rows[row][labels - 1]
            now = CURLY_OPEN + was + CURLY_CLOSE if was else CURLY_OPEN + 'unnamed' + CURLY_CLOSE
        elif kind == 'PLACEHOLDER':
            column, was, now = labels, rows[row][labels - 1], PLACEHOLDER_TEXT
        elif kind == 'NEAR_DUPLICATE':
            column, was = labels, rows[row][labels - 1]
            # A character dropped from the middle of ANOTHER row's label, which is one edit and
            # therefore inside the audit's threshold of two. A trailing space - the first thing
            # tried - is trimmed before anything compares, so it made an exact duplicate and the
            # audit reported neither a near duplicate nor anything else.
            other = next((rows[r][labels - 1].strip() for r in available
                          if r not in used and len(rows[r][labels - 1].strip()) > 8), '')
            now = other[:len(other) // 2] + other[len(other) // 2 + 1:] if other else was
            if not other:
                raise SystemExit('no label in ' + tab + ' is long enough to make a near '
                                 'duplicate of (the audit needs at least 6 characters).')
        else:
            raise SystemExit('no such kind: ' + kind)
        while len(rows[row]) < column:
            rows[row].append('')
        rows[row][column - 1] = now
        done.append({'sheet': tab, 'row': number, 'column': column, 'kind': kind,
                     'was': was, 'now': now})
    return done


def write_tsv(path, rows):
    folder = os.path.dirname(path)
    if folder and not os.path.isdir(folder):
        os.makedirs(folder)
    out = []
    for row in rows:
        cells = []
        for cell in row:
            value = (cell or '').replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
            if '"' in value:
                value = '"' + value.replace('"', '""') + '"'
            cells.append(value)
        out.append('\t'.join(cells))
    io.open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    workbook, into = argv[1], argv[2]
    classpath = argv[3] if len(argv) > 3 else None
    tabs = read_tabs(workbook)

    pristine = {}
    for each in TASKS:
        name = each['tab']
        if name not in tabs:
            raise SystemExit('the workbook has no tab called ' + name + '; it has: '
                             + ', '.join(sorted(tabs))[:400])
        trimmed = trim(tabs[name])
        tidied = clean(trimmed, 1, label_column(trimmed[1]) or 3)
        print('%s: %d candidate rows; cleaned %d typographic, %d whitespace, %d out-of-set '
              'reference cells' % (name, len(trimmed) - 2, tidied['typographic'],
                                   tidied['whitespace'], tidied['references']))
        pristine[name] = trimmed
        write_tsv(os.path.join(into, 'pristine', name + '.tsv'), trimmed)

    if classpath:
        dropped = {}
        scrub(os.path.join(into, 'pristine'), pristine, classpath, dropped=dropped)
        for name in pristine:
            if len(pristine[name]) - 2 < ROWS:
                raise SystemExit('%s has only %d clean rows left of %d candidates; raise '
                                 'CANDIDATES' % (name, len(pristine[name]) - 2, CANDIDATES))
            del pristine[name][2 + ROWS:]
            write_tsv(os.path.join(into, 'pristine', name + '.tsv'), pristine[name])
        # Cutting to size orphans references from the rows that are kept to the rows that are
        # not, so the folder has to be scrubbed again after it is cut.
        scrub(os.path.join(into, 'pristine'), pristine, classpath, dropped=dropped)
        for name in pristine:
            if len(pristine[name]) - 2 != ROWS:
                raise SystemExit('%s ended with %d rows, not %d'
                                 % (name, len(pristine[name]) - 2, ROWS))
        print('pristine: %d rows each, dropped %s, and the audit reports nothing'
              % (ROWS, dropped or 'none'))
    else:
        print()
        print('NO CLASSPATH GIVEN, so the trimmed tabs have NOT been checked against the audit.')
        print('They will still hold references to rows the trimming cut, and the six seeded')
        print('defects will be lost among them. Pass a classpath as the third argument:')
        print('  python -I tools/study/make-tasks.py <workbook> <out> '
              '"target/classes:$(cat target/cp.txt)"')
        print()

    key = []
    for each in TASKS:
        name = each['tab']
        damaged = [list(r) for r in pristine[name]]
        for defect in seed(damaged, name):
            defect['task'] = each['task']
            key.append(defect)
        write_tsv(os.path.join(into, each['task'], name + '.tsv'), damaged)
        print('%s/%s.tsv  %d data rows, %d defects' % (each['task'], name, ROWS, len(SEEDS)))

    if classpath:
        for each in TASKS:
            found = audit(os.path.join(into, each['task']), classpath)
            kinds = {}
            for one in found:
                kinds[one['kind']] = kinds.get(one['kind'], 0) + 1
            wanted = dict((s['kind'], 1) for s in SEEDS)
            print('task %s: the audit reports %s' % (each['task'], kinds))
            if kinds != wanted:
                raise SystemExit('task %s does not hold exactly the six seeded defects - wanted '
                                 '%s. It would not be scoreable.' % (each['task'], wanted))

    columns = ['task', 'sheet', 'row', 'column', 'kind', 'was', 'now']
    write_tsv(os.path.join(into, 'answer-key.tsv'),
              [columns] + [[str(d[c]) for c in columns] for d in key])

    lines = ['# Answer key', '',
             'Generated by tools/study/make-tasks.py. Do not show this to a participant.', '',
             'Six defects per task set, one of each kind, at matched row offsets. "Was" is what',
             'the cell held in the real workbook, which is the fix; any other edit that clears',
             'the finding also counts, and tools/study/score.py says which happened.', '']
    for task in ('A', 'B'):
        lines.append('## Task set ' + task)
        lines.append('')
        lines.append('| row | column | kind | should become | was seeded as |')
        lines.append('|---|---|---|---|---|')
        for d in key:
            if d['task'] != task:
                continue
            lines.append('| %d | %d | %s | `%s` | `%s` |'
                         % (d['row'], d['column'], d['kind'],
                            (d['was'] or '(empty)')[:60], (d['now'] or '(empty)')[:60]))
        lines.append('')
    io.open(os.path.join(into, 'answer-key.md'), 'w', encoding='utf-8',
            newline='\n').write('\n'.join(lines) + '\n')
    print('answer-key.tsv and answer-key.md written to', into)
    print()
    print('Now check it. The pristine tabs must report NO findings, and each task set')
    print('exactly six - one per kind. Anything else and the task is not the task:')
    print('  java -cp "target/classes:$(cat target/cp.txt)" \\')
    print('       de.fizkarlsruhe.ise.ontoboard.bench.Bench summary ' + into + '/pristine')
    for each in TASKS:
        print('  java -cp "target/classes:$(cat target/cp.txt)" \\')
        print('       de.fizkarlsruhe.ise.ontoboard.bench.Bench summary '
              + into + '/' + each['task'])


if __name__ == '__main__':
    main(sys.argv)
