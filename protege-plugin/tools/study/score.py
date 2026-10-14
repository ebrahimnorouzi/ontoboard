"""Scores one participant's submitted tab against the answer key, and counts the damage.

Two numbers come out of a repair task, and only one of them is obvious.

  DEFECTS CLEARED, out of six. Measured by running the shipped audit on what the participant
  handed back, not by comparing their cell to the expected string: there is more than one right
  way to clear a finding, and a scorer that only accepts the original value would mark a correct
  curator wrong. If the audit no longer reports that kind at that cell, it is cleared.

  COLLATERAL CHANGES. Every cell they changed that was not one of the six, and - separately -
  every NEW finding their edits introduced. This is the number a usability paper usually leaves
  out, and it is the one that decides whether a tool that makes people faster also makes them
  more careless. A participant who clears six defects and breaks four references has not done
  better than one who clears four and breaks none.

Usage, from protege-plugin:

    python -I tools/study/score.py \\
        --key target/study/answer-key.tsv \\
        --pristine target/study/pristine \\
        --submitted path/to/participant-07/People.tsv \\
        --task A \\
        --classpath "target/classes:$(cat target/cp.txt)"

It writes one TSV row to standard output, so a session's scores concatenate into the table the
analysis script reads.
"""
import io
import os
import subprocess
import sys
import tempfile


def read_tsv(path):
    rows = []
    for line in io.open(path, encoding='utf-8', newline='').read().replace('\r\n', '\n').split(
            '\n'):
        if line == '' and rows:
            continue
        rows.append(split_tsv(line))
    while rows and rows[-1] == ['']:
        rows.pop()
    return rows


def split_tsv(line):
    """One line into cells, honouring the quoting the editor writes."""
    cells, cell, quoted, at = [], [], False, 0
    while at < len(line):
        here = line[at]
        if quoted:
            if here == '"':
                if at + 1 < len(line) and line[at + 1] == '"':
                    cell.append('"')
                    at += 1
                else:
                    quoted = False
            else:
                cell.append(here)
        elif here == '"' and not cell:
            quoted = True
        elif here == '\t':
            cells.append(''.join(cell))
            cell = []
        else:
            cell.append(here)
        at += 1
    cells.append(''.join(cell))
    return cells


def audit(folder, classpath):
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


def cell(rows, row, column):
    if row - 1 < 0 or row - 1 >= len(rows):
        return None
    line = rows[row - 1]
    return line[column - 1] if column - 1 < len(line) else ''


def argument(name, default=None):
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    if default is None:
        raise SystemExit(__doc__)
    return default


def main():
    key_path = argument('--key')
    pristine = argument('--pristine')
    submitted = argument('--submitted')
    task = argument('--task')
    classpath = argument('--classpath')
    who = argument('--participant', os.path.basename(os.path.dirname(submitted)) or '?')

    key = read_tsv(key_path)
    head = key[0]
    defects = [dict(zip(head, r)) for r in key[1:] if len(r) == len(head)
               and dict(zip(head, r))['task'] == task]
    if not defects:
        raise SystemExit('the key has no defects for task ' + task)
    tab = defects[0]['sheet']

    handed_back = read_tsv(submitted)
    before = read_tsv(os.path.join(pristine, tab + '.tsv'))

    # The audit wants a folder, and the folder it is given must hold nothing else.
    folder = tempfile.mkdtemp(prefix='ontoboard-score-')
    try:
        io.open(os.path.join(folder, tab + '.tsv'), 'w', encoding='utf-8', newline='\n').write(
            io.open(submitted, encoding='utf-8', newline='').read())
        found = audit(folder, classpath)
    finally:
        for name in os.listdir(folder):
            os.remove(os.path.join(folder, name))
        os.rmdir(folder)

    still_there = set()
    for one in found:
        still_there.add((one['kind'], one['row'], one['column']))

    cleared, missed = 0, []
    for defect in defects:
        here = (defect['kind'], defect['row'], defect['column'])
        if here in still_there:
            missed.append(defect['kind'])
        else:
            cleared += 1

    seeded_cells = set((int(d['row']), int(d['column'])) for d in defects)
    collateral, restored = 0, 0
    for row in range(3, max(len(before), len(handed_back)) + 1):
        width = max(len(before[row - 1]) if row - 1 < len(before) else 0,
                    len(handed_back[row - 1]) if row - 1 < len(handed_back) else 0)
        for column in range(1, width + 1):
            was = cell(before, row, column)
            now = cell(handed_back, row, column)
            if was is None or now is None:
                collateral += 1
                continue
            if was == now:
                continue
            if (row, column) in seeded_cells:
                restored += 1
            else:
                collateral += 1

    introduced = [one for one in found
                  if (one['kind'], one['row'], one['column']) not in
                  set((d['kind'], d['row'], d['column']) for d in defects)]

    columns = ['participant', 'task', 'sheet', 'cleared', 'of', 'missed',
               'cells_changed_outside_the_defects', 'new_findings_introduced', 'rows_submitted']
    values = [who, task, tab, str(cleared), str(len(defects)),
              ';'.join(missed) or '-', str(collateral), str(len(introduced)),
              str(max(0, len(handed_back) - 2))]
    print('\t'.join(columns))
    print('\t'.join(values))
    if introduced:
        for one in introduced[:10]:
            sys.stderr.write('introduced\t%s\trow %s\tcolumn %s\n'
                             % (one['kind'], one['row'], one['column']))


if __name__ == '__main__':
    main()
