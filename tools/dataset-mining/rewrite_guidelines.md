# Rewrite Guidelines

Public datasets are used to inspire coverage, not to provide committed dialogue text.

## Do

- Preserve task shape: accept, reject, counter-offer, answer question, emotion share, repair, topic shift.
- Rewrite into CampusPulse context: library, canteen, playground, dorm, rain, night walk, hot drink, class pressure.
- Keep each case short and inspectable.
- Store expected local-rule output explicitly.
- Put only critical boundaries in `expect.must` / `expect.mustNot`.
- Put preferred labels, confidence trends, and score tendencies in `expect.should`.
- Use `sourceInspiredBy` to record the dataset family that inspired the structure.

## Do Not

- Do not paste original dataset utterances into committed JSONL.
- Do not keep personal or identifiable information.
- Do not use external dataset labels as final truth if they conflict with CampusPulse semantics.
- Do not make every generated variation mandatory forever; weak cases can be moved to a diagnostic set later.

## Mapping Notes

### CrossWOZ

Task-oriented state transitions map well to:

- accept plan
- reject/cancel plan
- counter-offer
- destination update
- active objective
- scene transition needed

### CPED

Persona, emotion, scene, and dialogue-act labels map well to:

- emotional sharing
- romantic probe
- quality question
- gender/persona consistency
- relationship scoring signals

### NaturalConv

Topic-driven turns map well to:

- topic-only place references
- smooth topic continuation
- topic shift without movement
- plot signal from memory/topic continuity

### MPDD

Emotion and interpersonal relation signals map well to:

- boundary respect
- control pressure
- low effort dismissive replies
- repair after tension
- heartbeat continuation
