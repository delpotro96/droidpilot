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
| 1 | Observer and serializer | verified on a Galaxy S25+ |
| 2 | Executor and policy | unit tested; gestures not yet seen on a device |
| 3 | Trajectory record and replay | unit tested |
| 4 | Planner (LLM) | verified against a live llama.cpp server |
| 5 | Agent loop and UI | verified end to end without a phone, see below |
| 6 | Vision escalation | capture verified on a real game; coordinate accuracy is poor on a 3B model |
| 7 | Shortcut tools | not started |

Two dumps from a real phone settled most of the guesswork. A Unity game
returns exactly one element, a `SurfaceView` that is not even clickable, and
it can be photographed — no `FLAG_SECURE`. A KakaoTalk conversation returns
sixty-five elements where every message bubble is its own clickable button
carrying its text directly, and the compose bar is last in the tree.

## Design notes

- **Text serialization is the default path.** Flattening the view tree into a
  numbered listing lets a text-only model drive the agent, with no vision
  model in the loop.
- **Vision is the fallback.** A game draws its whole interface into one
  `SurfaceView`, which defeats the view tree. When `ScreenState.isTextUsable`
  is false the observer attaches a screenshot, sent as an `image_url` part on
  `/v1/chat/completions`. The older `/completion` endpoint took an
  `image_data` array and silently drops it on a current server, which reads
  as a weak model rather than a blind one.
- **A flat capture means the screen is secured.** `FLAG_SECURE` makes
  `takeScreenshot()` succeed and return one colour. That capture is discarded,
  and a screen that can be neither listed nor captured stops the run instead
  of being guessed at.
- **`AgentAction` is a sealed interface.** The planner cannot emit free-form
  text. `AskUser` is a first-class action so an unsure planner asks rather
  than tapping at random. There is no `wait`: it was the answer a small model
  reached for whenever it could not decide, and a screen that has not moved
  because nothing was pressed looks exactly like one that is loading.
- **The planner declares what its press will do.** Every press carries a risk
  of `none`, `spends` or `irreversible`, and the grammar will not let it be
  omitted. Five attempts at inferring danger from the label failed in both
  directions at once, because the string on screen does not carry the
  information — the model does. Keyword rules remain as a net that can raise a
  verdict the planner played down, never lower one.
- **The agent opens the app itself.** Without that, a run began with the
  person unlocking the phone and finding the right screen, at which point
  there was very little left worth automating. Packages come from the phone
  and are offered as a closed list, because a guessed one starts nothing and
  reports nothing.
- **Its own screen is not something to operate.** Shown our interface, the
  planner read the goal box as somewhere to type the goal and did that until
  the budget ran out. The screen is no longer described to it, and the policy
  refuses everything there except leaving.
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
```

The suite runs on the JVM through Robolectric, so a device is only needed to
exercise the accessibility service itself.

## Checking a change without a phone

`LiveAgentTest` runs the whole agent — loop, policy, prompt, grammar — against
a live model on screens taken from a real phone. It skips itself when no
server is reachable, so an ordinary build does not depend on one.

```
DROIDPILOT_PLANNER=http://127.0.0.1:18080 ./gradlew :app:testDebugUnitTest --tests "dev.droidpilot.LiveAgentTest"
```

This exists because eight builds went out in one day, each one a guess that
could only be checked by handing someone an apk. The first run of this test
found three faults that would all have shipped.

## Planner

A llama.cpp server reached over plain HTTP.

```
llama-server -m Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf \
  --mmproj mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf \
  --host 0.0.0.0 --port 18080 -ngl 99 -c 8192
```

**http, never https.** Offered a TLS handshake the server cannot read one,
never answers, and the run waits on the negotiation until it times out, which
looks exactly like a firewall problem and is not one.

Requests carry a GBNF grammar that constrains decoding to the action schema,
so the model physically cannot emit malformed JSON. That is the first thing
small models get wrong, and removing the failure mode outright is what makes
a 3B model viable here.

Two things about small models that cost more than they should have:

- The example in the prompt steers harder than any instruction. Shown a `tap`
  example the model tapped, and where there was nothing worth tapping it
  swiped instead. The example matches the decision being asked for.
- A reasoning model puts its answer in `reasoning_content` and leaves
  `content` empty. Both are read.

Measured on a GTX 1050 Ti, 4GB: a text screen decides in one to two seconds,
a screenshot at 1024px in about twenty. At 1568px it is nearly a minute, and
below 1024 the model stops reading buttons and starts inventing them.

## Run

1. Install and open the app
2. `Open accessibility settings` and enable DroidPilot
3. Put the server URL in the first field — `http://`, not `https://`
4. Type a goal and press `Run`

The agent opens whatever app the goal names; there is nothing to switch to by
hand. A run keeps going after you leave the app, and the log and any
confirmation prompt are waiting when you come back.

`Dump screen in 5s` renders the current screen as text without running the
agent, and posts the full observation — bounds, resource ids, screenshot — to
the listener below.

## Listener

`tools/dump_listener.py` receives screen dumps and run logs from the phone,
and forwards `/v1/*` to the model server so only one port has to be reachable.

```
python tools/dump_listener.py
```

The on-screen listing drops bounds and resource ids, which are the fields
every policy decision is actually made on, so reading a screen by copying
that listing out by hand meant guessing at the rest. A run log matters most
when a run stalls, which is exactly when nobody can reach the phone.

## Guardrails

| Risk | Mitigation |
|---|---|
| Spending money | The planner declares `spends` and the keyword net raises anything it played down, both asking before the press. Banking apps and the stores are refused outright, wherever the request comes from |
| Sending a message to the wrong person | Confirmation before send, asked as a notification because the app is not in the foreground while the agent works. With notifications off the agent refuses rather than guessing |
| Pressing a screen nothing can read | A coordinate press is refused wherever anything actionable is listed, so it cannot be used to reach a button unread |
| Infinite loop, drained battery | Abort on 3 repeats of the same screen hash, 40 step budget, 20% battery floor, and a deadline on every observation and planner call |
| Operating itself | Our own screen is not described to the planner, and nothing but leaving is allowed there |

Denying a whole screen on a keyword was tried and removed. A messenger
renders arbitrary text as controls, so one friend typing 결제하기 locked an
entire conversation, while a real shopping page kept its button inside a
full-window scroll view and slipped past every structural test meant to tell
the two apart. Denying costs the whole run and could not be aimed; asking
costs a notification and always can.
