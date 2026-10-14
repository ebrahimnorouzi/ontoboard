<!--
  Written for: whoever runs a session, to be read beside the participant.

  The design and its reasoning are in ../evaluation-plan.md. This is only the running of it:
  what to say, in what order, and what to write down. Everything a facilitator improvises is a
  difference between participants that the analysis cannot separate from the thing being
  measured, so this is deliberately scripted.
-->

# Session protocol

**80 minutes, hard-timed.** One participant, one facilitator. Within-subject: every participant
uses both OntoBoard and the baseline (stock Protégé plus the ROBOT command line), on two matched
task sets, in an order decided by the counterbalancing sheet.

## Before the participant arrives

| | |
|---|---|
| Materials | `make-tasks.py` has been run, and both task sets report **exactly six findings, one per kind**. The generator refuses otherwise - if it refused, fix that before anybody arrives. |
| Machines | Two prepared profiles: OntoBoard installed in Protégé, and a baseline Protégé with no OntoBoard and a terminal with `robot` on the PATH. Both open the same ontology. |
| Recording | Screen and audio only. No camera. Check the recorder works on a throwaway run. |
| Paper | Consent sheet (two copies), task sheets A and B, SUS, RTLX, SEQ slips, and the observation sheet. |
| Timer | Visible to the facilitator, not to the participant. |

Do **not** read the answer key before the session. A facilitator who knows where the six defects
are will look at them, and the participant will follow the look.

## 0-10 min · Consent and background

1. Hand over [consent.md](consent.md). Let them read it. Answer questions. Both sign.
2. Start the recording only after the signature.
3. Background questions, read as written:
   - How long have you worked on ontologies, in years?
   - Which editors do you use? (open)
   - Have you used ROBOT from the command line? (never / tried it / regularly)
   - Have you used the Ontology Development Kit? (never / tried it / regularly)
   - Have you used OntoBoard before today? (never / seen a demo / used it)
   - On a scale of 1-5, how comfortable are you editing a ROBOT template spreadsheet?

   Anybody answering "used it" to OntoBoard is still welcome, and is recorded as such: their
   numbers go in the table with a flag, and the analysis reports both with and without them.

## 10-20 min · Training, equal for both conditions

Ten minutes, five per condition, in the order that condition will be used. Demonstrate on a
**third** tab that is not part of either task:

- opening the sheet, finding a row, editing a cell, saving
- running the check / running `robot report`
- generating OWL from the tab

Say: *"I will not help once the task starts, and that is not because I am being difficult - it is
so that what you do is yours and not mine. If you get stuck, say so out loud and move on."*

## 20-40 min · First condition, first task set

1. Hand over the task sheet. Read the task aloud once, then stop talking.
2. Start the timer. **Cap: 20 minutes.** Say nothing when the cap is reached except
   *"that is time, thank you - please stop where you are."*
3. Think-aloud: if they go quiet for more than about twenty seconds, say only
   *"what are you thinking?"* Nothing else. Write down every prompt you give.
4. On the observation sheet, record: each defect as they find it (time), each time they leave the
   editor for another window (a tally - this is the context-switch measure), and anything they
   say that sounds like a judgement of the tool.
5. Afterwards, in this order: SEQ for each of the three subtasks, then SUS, then RTLX.
6. Save their files into `participant-NN/condition-X/`.

## 40-60 min · Second condition, second task set

The same, with the other tool and the other task set. Do not mention anything they did in the
first half.

## 60-70 min · Task C, descriptive only

The collaboration task. No baseline: stock Protégé has no multi-user mode at all, which is
itself the finding and is reported as such rather than as a score of zero.

1. The participant edits an MWO module. A scripted confederate client makes three remote edits,
   at 2, 5 and 8 minutes: a `SubClassOf` (carried by the protocol), an `EquivalentClasses` (not
   carried), and an annotation (carried).
2. Record for each: did they notice, and at what time.
3. Then ask, and write the answer verbatim: *"Do you believe your own edits were safe? Why?"*
4. From the session log, count how many of **their** edits the protocol could not carry. This is
   the honest answer to a "conflict-free" claim, and it is a number rather than an assertion.

## 70-80 min · Debrief

1. *"What was the most annoying thing about each tool?"*
2. *"Was there a moment you did not trust what you were seeing?"*
3. *"If you could change one thing, what?"*
4. Tell them what the six defects were, per task set. People want to know, and it costs nothing
   once their data is collected.
5. Stop the recording. Note the file names.

## Scoring, afterwards

Do not score during the session.

```
python -I tools/study/score.py \
    --key target/study/answer-key.tsv \
    --pristine target/study/pristine \
    --submitted participant-07/ontoboard/People.tsv \
    --task A \
    --classpath "target/classes:$(cat target/cp.txt)" \
    --participant 07
```

It reports defects cleared out of six, which ones were missed, **cells changed that were not
defects**, and **new findings introduced**. The last two matter as much as the first: a
participant who clears six defects and breaks four references has not done better than one who
clears four and breaks none, and a tool that makes people faster at the cost of collateral damage
should be reported as doing exactly that.

## What to write down when something goes wrong

A session that deviates is still data, as long as the deviation is recorded. Note: the cap being
hit, a crash, a prompt you gave that is not in this script, a participant who had used OntoBoard
before, anything they were told by accident. Report the count of deviating sessions in the paper.
