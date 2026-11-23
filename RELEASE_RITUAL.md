# UniNVG Release Ritual

Every version goes through this exact sequence. Nothing ships until every box is checked.
The builder (Vibe) owns the execution; the version number belongs to the owner.

## 0. Constraints that apply to every change
- **Nothing that affects the image may be fixed.** No shutters caps, no locked ISO targets,
  no frozen exposure. The view must follow the light instantly (Cardboard use case).
- Device priority order: **S24 Exynos → S22 Exynos → J3 → A71 → Universal.**
- App is a pure viewfinder: no capture, no import, full-screen view, semi-transparent gear top-right.

## 1. Local static checks (no build machine here)
- `python3 -c "import yaml; ..."` parse on every touched workflow YAML.
- `bash -n` on run blocks extracted from edited workflows.
- Static review of every changed file before pushing.
- Remember the trap: the version-picker test stub must equal the gradle `versionName` default.

## 2. Branch + PR + CI
- Work on `vibe/<slug>-11aaad`, open a draft PR.
- CI must run `testDebugUnitTest lintDebug assembleDebug` and finish **green**.
- Single retry allowed for external flakes (Maven Central 429s, Robolectric download hiccups);
  a second failure of the same thing is a real bug to fix before continuing.

## 3. Self-testing — look at the app with my own eyes
- Download the `ui-screenshots` artifact on **every** run (it uploads on `always()`).
- Visually inspect every screenshot: gear position, HUD placement (portrait + landscape),
  no overlaps, Device tab chips/summary, Manual assist panel, Update tab, memory overlay.
- Read failed tests' HTML reports from artifacts when anything is red.

## 4. Merge + main verification
- After merge, CI on `main` must be green too.
- If a run died early (sub-minute, network/429 causes) re-trigger once and confirm green.

## 5. Tag + release verification
- Tag `vX.Y.Z[.W]` from `main` (4-part tags supported: code = MAJOR·1_000_000 + MINOR·10_000 + PATCH·100 + BUILD).
- Release workflow must finish **success** with:
  - log line `Releases will be signed with the stable UniNVG key` (never ephemeral debug signing),
  - `UniNVG-<tag>.apk` attached, release public (not draft),
  - correct version stamped from the tag.

## 6. Report to the owner
- Release URL, what changed, and the update path
  (with the stable key, updates install **in place** — no uninstall needed since v0.5.0).
- If an on-device update ever fails: get the exact error text before guessing
  (suspects in order: Play Protect interception, versionCode math, then signature).

## 7. Post-release
- Confirm the in-app updater's "Browse versions" catalog will list the new release.
- Any rough edges spotted during testing get fixed in the next cycle, not silently ignored.
