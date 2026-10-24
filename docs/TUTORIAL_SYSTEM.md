# Tutorial system (v2 — simplified)

Package: `app_cs/src/main/java/com/craftstudio/cslauncher/tutorial/`

## What it does
* **First launch:** one welcome card (`FirstLaunchSetup`, layout `tutorial_setup` + `tutorial_page_welcome`)
  with *Take the tour* / *Skip*. Either button marks onboarding done (`TutorialState`, prefs `cs_tutorial`,
  schema 2) — the card never returns on its own. Three unanswered appearances (crash / force stop) also mark it done.
* **Guides (5, guided only):** `home` (launcher tour, 10 stops on the home screen), `profiles`,
  `look` (Minecraft Appearance: Vanilla Experience vs CS Custom Experience), `content`, `settings`.
  Defined in `TutorialRegistry`; every target is a real view id, every text describes shipped behaviour.
* **Engine (`TutorialManager`):** navigate (launcher's own calls, polled until the fragment is resumed) →
  locate (bounded retries, scroll into view) → spotlight (`TutorialOverlay`). The overlay swallows all touches
  outside its card; Back asks "Stop the tour?". Failures end in Retry / Skip, never a stuck overlay.
  Activity recreation re-attaches and rebuilds the current step.
* **Tutorials screen (`TutorialCenterFragment`):** Settings → Miscellaneous → Tutorials. Start / Continue / Replay
  per guide, Resume banner for a paused run, More → *Replay the launcher tour* / *Reset progress*
  (touches only `cs_tutorial` prefs).
* **"?" button (`ContextualHelp`):** offers the guide for the current screen or the Tutorials screen. No automatic tips.

Strings: `res/values/strings_tutorial.xml` (+ Hinglish subset in `values-b+hi+Latn`).
