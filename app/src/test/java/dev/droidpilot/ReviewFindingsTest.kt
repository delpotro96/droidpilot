package dev.droidpilot

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.agent.AgentLoop
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.InstalledApp
import dev.droidpilot.core.model.Planner
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.Step
import dev.droidpilot.core.model.Verdict
import dev.droidpilot.planner.PlannerPrompt
import dev.droidpilot.policy.LoopGuard
import dev.droidpilot.policy.SafetyPolicy
import dev.droidpilot.serializer.ScreenSerializer
import dev.droidpilot.trajectory.FileTrajectoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

// Everything an adversarial read of one day's work turned up, written down so
// it cannot come back quietly. Each of these was reproduced before it was fixed
@RunWith(AndroidJUnit4::class)
class ReviewFindingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val policy = SafetyPolicy(ownPackage = "dev.droidpilot")
    private val performed = mutableListOf<AgentAction>()

    @Test
    fun `a bank is refused by the name that will actually be opened`() {
        // The prefix rescue that exists because small models drop the tail of
        // a package name also expanded viva.republica into the bank behind the
        // policy's back, and a trajectory would have replayed that forever
        val truncated = AgentAction.Launch("viva.republica")
        val full = AgentAction.Launch("viva.republica.toss")
        val state = screen(elements = listOf(element(0, "Home")))

        assertTrue(policy.check(full, state) is Verdict.Deny)

        // Resolution now happens where the app list lives, before the verdict,
        // so what the policy sees is what the executor will start
        val resolved = resolveLike(truncated, listOf(InstalledApp("Toss", "viva.republica.toss")))
        assertTrue(policy.check(resolved, state) is Verdict.Deny)
    }

    // Mirrors LlamaServerPlanner.resolveLaunch, which is private to it
    private fun resolveLike(action: AgentAction, apps: List<InstalledApp>): AgentAction {
        if (action !is AgentAction.Launch) return action
        if (apps.any { it.packageName == action.packageName }) return action
        val hits = apps.filter { it.packageName.startsWith(action.packageName) }
        return if (hits.size == 1) AgentAction.Launch(hits.first().packageName) else action
    }

    @Test
    fun `leaving is still possible while the screen is changing underneath`() = runTest {
        val browser = screen(packageName = "com.android.chrome", elements = listOf(element(0, "Go")))
        val elsewhere = screen(
            packageName = "com.other.app",
            hash = "elsewhere",
            elements = listOf(element(0, "Something else"))
        )

        var n = 0
        val agent = loop(
            { AgentAction.Home },
            screens = { if (n++ % 2 == 0) browser else elsewhere }
        )
        agent.run(Goal("get out of here", stepBudget = 4))

        // The package check sits before the action is examined, so pressing
        // home was being refused for the reason that the agent needed it
        assertTrue("home never ran: " + performed, performed.contains(AgentAction.Home))
    }

    @Test
    fun `one empty observation is a window changing, not a screen that cannot be read`() = runTest {
        val blank = screen(elements = emptyList())
        val real = screen(hash = "real", elements = listOf(element(0, "Settings")))

        var n = 0
        val agent = loop({ AgentAction.Done("arrived") }, screens = { if (n++ == 0) blank else real })
        val result = agent.run(Goal("open settings", stepBudget = 5))

        // rootInActiveWindow is null mid transition, which read identically to
        // a secured game and killed the run on the spot
        assertTrue("got " + result, result is AgentLoop.Result.Done)
    }

    @Test
    fun `a refusal does not spend the budget the person was promised`() {
        val history = (0 until 45).map {
            Step(AgentAction.Tap(0), "hash", succeeded = false, refused = true)
        }

        val prompt = PlannerPrompt.build(
            Goal("do something", stepBudget = 40),
            screen(elements = listOf(element(0, "Go"))),
            history
        )

        assertFalse("budget went negative:\n" + prompt, prompt.contains("Budget: -"))
        assertTrue(prompt, prompt.contains("Budget: 40 actions left"))
    }

    @Test
    fun `a refusal is not reported to the planner as a press that failed`() {
        val prompt = PlannerPrompt.build(
            Goal("do something"),
            screen(elements = listOf(element(0, "Go"))),
            listOf(Step(AgentAction.Tap(0), "hash", succeeded = false, refused = true))
        )

        // "tapped element 0 (failed)" tells the model the press happened and
        // did nothing, which is the opposite of what the policy did
        assertFalse(prompt, prompt.contains("(failed)"))
        assertTrue(prompt, prompt.contains("not allowed"))
    }

    @Test
    fun `the prompt asks for a point only where a point is allowed`() {
        // A video with a buy button beside it: not text usable, yet the button
        // is listed. The prompt told the planner to aim and the policy refused
        // every point, and the two Denies pointed at each other
        val stream = screen(
            elements = listOf(
                element(0, "player", role = Role.SURFACE, clickable = false,
                    left = 0, top = 0, right = 1080, bottom = 1400),
                element(1, "구매", left = 0, top = 1900, right = 1080, bottom = 2000)
            )
        )

        assertTrue(stream.hasNameableTarget)
        assertFalse(
            PlannerPrompt.build(Goal("buy it"), stream, emptyList()).contains("tapAt")
        )
    }

    @Test
    fun `a wildly tall element does not empty the bottom of the listing`() {
        val bubbles = (0 until 95).map {
            element(it, "message " + it, left = 0, top = it * 20, right = 900, bottom = it * 20 + 60)
        }
        val runaway = element(95, "webview", role = Role.WEBVIEW, clickable = false,
            left = 0, top = 0, right = 1080, bottom = 20_000)
        val send = element(96, "전송", left = 900, top = 2250, right = 1080, bottom = 2380)

        val kept = ScreenSerializer.choose(bubbles + runaway + send, displayHeight = 2400)

        // Bounds are not clipped to the display, so the tallest element was
        // putting the band at 17000 and taking the tail with it
        assertTrue("send was dropped", kept.any { it.label == "전송" })
        assertTrue("cap exceeded: " + kept.size, kept.size <= 84)
    }

    private fun loop(
        decide: () -> AgentAction,
        screens: () -> ScreenState
    ): AgentLoop {
        val executor = object : Executor {
            override suspend fun perform(action: AgentAction, state: ScreenState): Result<Unit> {
                performed += action
                return Result.success(Unit)
            }
        }
        val planner = object : Planner {
            override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>) = decide()
        }

        return AgentLoop(
            planner = planner,
            executor = executor,
            policy = policy,
            store = FileTrajectoryStore(File(temp.newFolder(), "t.json")),
            guardFactory = { budget -> LoopGuard(ApplicationProvider.getApplicationContext(), budget) },
            observe = { screens() },
            confirm = { true },
            settleMillis = 0L
        )
    }

    @Test
    fun `the budget line never reads as a negative number`() {
        val history = (0 until 200).map { Step(AgentAction.Tap(0), "h", succeeded = true) }
        val prompt = PlannerPrompt.build(
            Goal("do something", stepBudget = 40),
            screen(elements = listOf(element(0, "Go"))),
            history
        )

        assertTrue(prompt, prompt.contains("Budget: 0 actions left"))
        assertEquals(0, Regex("Budget: -").findAll(prompt).count())
    }
}
