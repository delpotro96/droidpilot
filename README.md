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
| 3 | Trajectory record and replay | written, covered by unit tests |
| 4 | Planner (LLM) | written, covered by unit tests |
| 5 | Agent loop and UI | written, covered by unit tests |
| 6 | Vision escalation | wired, needs a multimodal server to mean anything |
| 7 | Shortcut tools | not started |

## Design notes

- **Text serialization is the default path.** Flattening the view tree into a
  numbered listing lets a text-only model drive the agent, with no vision
  model in the loop.
- **Vision is the fallback.** Unity and Unreal games expose the entire screen
  as a single `SurfaceView`, which defeats the view tree. When
  `ScreenState.isTextUsable` is false the observer attaches a screenshot and
  the planner sends it as `image_data`. This only does something if the server
  has a multimodal model loaded; against a text model the request still runs
  and the image is ignored.
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
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

65 unit tests run on the JVM through Robolectric, so a device is only needed
to exercise the accessibility service itself.

## Planner

The planner is a llama.cpp server, reached over HTTP. Point it at the phone,
at a machine on the same network, or anywhere else:

```kotlin
LlamaServerPlanner(baseUrl = "http://192.168.0.10:8080")
```

Requests carry a GBNF grammar that constrains decoding to the action schema,
so the model physically cannot emit malformed JSON. That is the first thing
small models get wrong, and removing the failure mode outright is what makes
a 4B model viable here.

## Run

Start a llama.cpp server somewhere the phone can reach:

```
llama-server -m qwen3-4b-instruct-q4_k_m.gguf --host 0.0.0.0 --port 8080
```

Then:

1. Install and open the app
2. `Open accessibility settings` and enable DroidPilot
3. Put the server URL in the first field
4. Type a goal and press `Run`, then switch to the app it should operate

`Dump screen in 5s` renders the current screen as text without running the
agent, which is the quickest way to see what the planner is given.

A run keeps going after you leave the app, because leaving is the point. The
log and any confirmation prompt are waiting when you come back.

## Guardrails

| Risk | Mitigation |
|---|---|
| Mis-tapping a payment button | Policy denies every action on a checkout screen |
| Sending a message to the wrong person | Confirmation required before send |
| Infinite loop, drained battery | Abort on 3 repeats of the same screen hash, 40 step budget, 20% battery floor |
| Screen contents leaking | Banking apps are refused outright. `SafetyPolicy` also takes an allow list, which nothing populates yet |
