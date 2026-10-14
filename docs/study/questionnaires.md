<!--
  Written for: whoever prints the slips and whoever analyses them.

  The exact items, in the exact order, with the exact scoring. Reproduced in full because a
  paraphrased SUS is not SUS and its number is not comparable to anybody else's - which is the
  entire reason SUS was chosen. The choice of instruments and the reasoning is in
  ../evaluation-plan.md section 2.6.
-->

# Questionnaires

Three instruments, in this order, after **each** condition: SEQ per subtask, then SUS, then RTLX.
About four minutes in total. Print one slip per condition per participant, headed with the
participant number and which tool it refers to - not the tool's name if you can avoid it, since
"OntoBoard" on the sheet is a cue.

## SEQ - single ease question

Asked immediately after each of the three subtasks, so three per condition.

> Overall, how difficult or easy was that task?
>
> 1 (very difficult) · 2 · 3 · 4 · 5 · 6 · 7 (very easy)

Reported as a median per subtask per condition, with the range. Seven points, one item, fifteen
seconds - its value is that it shows per-subtask difficulty that an aggregate hides.

## SUS - system usability scale

Ten items, strongly disagree (1) to strongly agree (5). **Do not reword them** and do not change
the order: the odd/even alternation is what the scoring depends on.

| # | Item |
|---|---|
| 1 | I think that I would like to use this system frequently. |
| 2 | I found the system unnecessarily complex. |
| 3 | I thought the system was easy to use. |
| 4 | I think that I would need the support of a technical person to be able to use this system. |
| 5 | I found the various functions in this system were well integrated. |
| 6 | I thought there was too much inconsistency in this system. |
| 7 | I would imagine that most people would learn to use this system very quickly. |
| 8 | I found the system very cumbersome to use. |
| 9 | I felt very confident using the system. |
| 10 | I needed to learn a lot of things before I could get going with this system. |

**Scoring.** For odd-numbered items subtract 1 from the response. For even-numbered items subtract
the response from 5. Sum the ten adjusted scores and multiply by 2.5, giving 0-100. It is **not a
percentage** and must not be reported as one.

Report mean, SD, median, and the Sauro-Lewis grade and percentile. The reason this instrument is
here and USE is not: it makes the number directly comparable to **CoModIDE's published 36.67 for
stock Protégé and 73.33 for CoModIDE**, the only peer-reviewed Protégé-plugin usability figures
in existence. Comparability is the whole point, so a 68 means something and a USE mean would not.

## RTLX - raw NASA task load index

Six items, 0-100, marked on a line with 21 ticks. Unweighted: the pairwise weighting procedure
costs ten minutes and buys nothing at this sample size, and the raw version is a published
instrument in its own right.

| Item | Question | 0 | 100 |
|---|---|---|---|
| Mental demand | How mentally demanding was the task? | very low | very high |
| Physical demand | How physically demanding was the task? | very low | very high |
| Temporal demand | How hurried or rushed was the pace of the task? | very low | very high |
| Performance | How successful were you in accomplishing what you were asked to do? | perfect | failure |
| Effort | How hard did you have to work to accomplish your level of performance? | very low | very high |
| Frustration | How insecure, discouraged, irritated, stressed and annoyed were you? | very low | very high |

**Note the Performance anchors**: 0 is *perfect*, 100 is *failure*. They run the opposite way to
the others and are reproduced that way on purpose - reversing them to "look consistent" is a
common error that quietly inverts one sixth of the score.

RTLX is the unweighted mean of the six. Lower is better. Report per item as well as the mean: a
tool that lowers mental demand while raising frustration is worth knowing about.

## The order matters, and why it is this one

SEQ first, because it is about a task that has just finished and the memory decays. SUS before
RTLX, because RTLX asks how hard they worked and answering it first colours the usability answers.
Keep the order identical for every participant - an order that varies is a variable nobody is
controlling.

## What goes in the spreadsheet

One row per participant per condition, with these columns, so that
[../evaluation-plan.md](../evaluation-plan.md)'s tables can be filled without rekeying anything:

```
participant  condition  order  task_set  seq1  seq2  seq3
sus1 ... sus10  sus_score
tlx_mental  tlx_physical  tlx_temporal  tlx_performance  tlx_effort  tlx_frustration  rtlx
minutes_task1  minutes_task2  minutes_task3  hit_cap
defects_cleared  defects_missed  cells_changed_outside_the_defects  new_findings_introduced
context_switches  deviations
```

The last four columns come out of `tools/study/score.py` and the observation sheet, not out of a
questionnaire. Keep the raw item responses, not only the computed scores: a reviewer may ask for a
per-item breakdown, and OntoEditor's inability to give one is a criticism this study should not
invite.
