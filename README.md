# DroidPilot

A personal agent that carries out tasks on Android from a natural language
instruction.

## How it works

```
instruction (natural language)
  -> Router          take a registered shortcut if one exists
  -> Universal Loop  observe -> serialize -> plan -> policy -> execute -> repeat
  -> Trajectory      store the successful path, replay it next time (no LLM calls)
```

The universal loop is the product. Shortcuts are an optimization, and
everything has to work with none of them registered.

## Status

| Stage | Scope | State |
|---|---|---|
| 1 | Observer and serializer | written, not verified on a device |
| 2 | Executor and policy | written, not verified on a device |
| 3 | Trajectory record and replay | not started |
| 4 | Planner (LLM) | not started |
| 5 | Shortcut tools | not started |

## Design notes

- **Text serialization is the default path.** Flattening the view tree into a
  numbered listing lets a text-only model drive the agent, with no vision
  model in the loop.
- **Vision is the fallback.** Unity and Unreal games expose the entire screen
  as a single `SurfaceView`, which defeats the view tree. When
  `ScreenState.isTextUsable` is false, the agent escalates to a screenshot.
- **`AgentAction` is a sealed interface.** The planner cannot emit free-form
  text. `AskUser` is a first-class action so an unsure planner asks rather
  than tapping at random.
- **Inference stays local.** llama.cpp with a GBNF grammar constraining the
  output to the action schema. The `Planner` interface swaps between
  on-device, a home server, and a cloud endpoint.

## Build

Requires JDK 17 and Android SDK 35. Android Studio is not needed, the
command line tools are enough. `minSdk` is 30 because `takeScreenshot()`
was added in API 30.

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Run

1. Install and open the app
2. `Open accessibility settings` and enable DroidPilot
3. Press `Dump screen in 5s` and switch to the app you want to inspect
4. Come back and the screen is rendered as text

## Guardrails

| Risk | Mitigation |
|---|---|
| Mis-tapping a payment button | Policy denies every action on a checkout screen |
| Sending a message to the wrong person | Confirmation required before send |
| Infinite loop, drained battery | Abort on 3 repeats of the same screen hash, 40 step budget, 20% battery floor |
| Screen contents leaking | Per-app allow list. Messaging and finance stay on the text path |
