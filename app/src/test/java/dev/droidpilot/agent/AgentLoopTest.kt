package dev.droidpilot.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.GridPoint
import dev.droidpilot.core.model.Planner
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.core.model.Step
import dev.droidpilot.element
import dev.droidpilot.policy.LoopGuard
import dev.droidpilot.policy.SafetyPolicy
import dev.droidpilot.screen
import dev.droidpilot.trajectory.ElementRef
import dev.droidpilot.trajectory.FileTrajectoryStore
import dev.droidpilot.trajectory.RecordedAction
import dev.droidpilot.trajectory.RecordedStep
import dev.droidpilot.trajectory.ScreenSignature
import dev.droidpilot.trajectory.Trajectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentLoopTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val performed = mutableListOf<AgentAction>()
    private val confirmations = mutableListOf<String>()

    private val executor = object : Executor {
        override suspend fun perform(action: AgentAction, state: ScreenState): Result<Unit> {
            performed += action
            return Result.success(Unit)
        }
    }

    private fun scriptedPlanner(vararg actions: AgentAction) = object : Planner {
        private var index = 0
        override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction =
            actions.getOrElse(index++) { AgentAction.Fail("script exhausted") }
    }

    private val defaultScreen = screen(
        elements = listOf(element(0, "Settings"), element(1, "Profile"), element(2, "Help"))
    )

    private fun newStore() = FileTrajectoryStore(File(temp.newFolder(), "t.json"))

    private fun loop(
        planner: Planner,
        store: FileTrajectoryStore = newStore(),
        confirmAnswer: Boolean = true,
        screens: List<ScreenState> = listOf(defaultScreen)
    ): Pair<AgentLoop, FileTrajectoryStore> {
        var observation = 0
        val agent = AgentLoop(
            planner = planner,
            executor = executor,
            policy = SafetyPolicy(),
            store = store,
            guardFactory = { budget -> LoopGuard(ApplicationProvider.getApplicationContext(), budget) },
            observe = { screens[minOf(observation++, screens.lastIndex)] },
            confirm = { reason -> confirmations += reason; confirmAnswer },
            settleMillis = 0L
        )
        return agent to store
    }

    private fun storedTap(
        goal: String,
        label: String,
        anchors: List<String>,
        activity: String? = "MainActivity",
        packageName: String = "com.example.app"
    ) =
        Trajectory(
            id = "t1",
            goal = goal,
            steps = listOf(
                RecordedStep(
                    RecordedAction.Tap(ElementRef(label, Role.BUTTON, 0, 0, 100, 50)),
                    ScreenSignature(packageName, activity, anchors)
                )
            ),
            recordedAt = 0L
        )

    @Test
    fun `a planned run executes actions until done`() = runTest {
        val (agent, _) = loop(scriptedPlanner(AgentAction.Tap(0), AgentAction.Done("opened")))

        val result = agent.run(Goal("open settings"))

        assertEquals(AgentLoop.Result.Done("opened", replayed = false), result)
        assertEquals(listOf(AgentAction.Tap(0)), performed)
    }

    @Test
    fun `a successful run is stored for replay`() = runTest {
        val (agent, store) = loop(scriptedPlanner(AgentAction.Tap(0), AgentAction.Done("opened")))

        agent.run(Goal("open settings"))

        assertEquals(1, store.findFor("open settings")?.steps?.size)
    }

    @Test
    fun `a stored path replays without consulting the planner`() = runTest {
        val store = newStore()
        store.save(storedTap("open settings", "Settings", listOf("Settings", "Profile", "Help")))

        val refusingPlanner = object : Planner {
            override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction =
                throw AssertionError("planner must not run when a path replays")
        }

        // The screen has to move, otherwise replay correctly reports that the
        // tap did nothing
        val (agent, _) = loop(
            refusingPlanner,
            store,
            screens = listOf(defaultScreen, screen(hash = "after", elements = listOf(element(0, "Back"))))
        )
        val result = agent.run(Goal("open settings"))

        assertTrue(result is AgentLoop.Result.Done)
        assertTrue((result as AgentLoop.Result.Done).replayed)
        assertEquals(listOf(AgentAction.Tap(0)), performed)
    }

    @Test
    fun `a replay that never moved the screen is neither success nor failure`() = runTest {
        val store = newStore()
        store.save(storedTap("open settings", "Settings", listOf("Settings", "Profile", "Help")))

        val refusingPlanner = object : Planner {
            override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction =
                throw AssertionError("every step already ran, replanning risks repeating it")
        }

        val (agent, _) = loop(refusingPlanner, store)
        val result = agent.run(Goal("open settings"))

        // Every step ran, so this is not an error. But a path never seen to do
        // anything is counted apart, and retires on that count alone
        assertTrue(result is AgentLoop.Result.Done)
        assertEquals(0, store.all().first().failureCount)
        assertEquals(1, store.all().first().unverifiedCount)
    }

    @Test
    fun `a stale path falls back to the planner`() = runTest {
        val store = newStore()
        store.save(storedTap("open settings", "Gone", listOf("Gone", "Vanished"), activity = "OldScreen"))

        val (agent, _) = loop(scriptedPlanner(AgentAction.Tap(1), AgentAction.Done("recovered")), store)
        val result = agent.run(Goal("open settings"))

        assertEquals(AgentLoop.Result.Done("recovered", replayed = false), result)

        // The replanned path supersedes the stale one rather than living beside it
        val stored = store.all()
        assertEquals(1, stored.size)
        assertNotEquals("t1", stored.first().id)
        assertEquals(0, stored.first().failureCount)
    }

    @Test
    fun `a denied action stops the run and never reaches the screen`() = runTest {
        val bank = screen(
            packageName = "viva.republica.toss",
            elements = listOf(element(0, "송금하기"), element(1, "취소"))
        )
        // Kept choosing it, so the refusals run out and the run ends
        val stubborn = object : Planner {
            override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>) =
                AgentAction.Tap(0)
        }
        val (agent, _) = loop(stubborn, screens = listOf(bank))

        val result = agent.run(Goal("send money"))

        assertTrue("got " + result, result is AgentLoop.Result.Blocked)
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a declined confirmation stops the run`() = runTest {
        val deletable = screen(elements = listOf(element(0, "삭제"), element(1, "Cancel"), element(2, "Help")))
        val (agent, _) = loop(
            scriptedPlanner(AgentAction.Tap(0)),
            confirmAnswer = false,
            screens = listOf(deletable)
        )

        val result = agent.run(Goal("clean up"))

        assertTrue(result is AgentLoop.Result.Blocked)
        assertTrue(performed.isEmpty())
        assertEquals(1, confirmations.size)
    }

    @Test
    fun `an accepted confirmation lets the action through`() = runTest {
        val deletable = screen(elements = listOf(element(0, "삭제"), element(1, "Cancel"), element(2, "Help")))
        val (agent, _) = loop(
            scriptedPlanner(AgentAction.Tap(0), AgentAction.Done("removed")),
            confirmAnswer = true,
            screens = listOf(deletable)
        )

        val result = agent.run(Goal("clean up"))

        assertTrue(result is AgentLoop.Result.Done)
        assertEquals(listOf(AgentAction.Tap(0)), performed)
    }

    @Test
    fun `an unsure planner surfaces the question instead of tapping`() = runTest {
        val (agent, _) = loop(scriptedPlanner(AgentAction.AskUser("which chat?")))

        val result = agent.run(Goal("reply to someone"))

        assertEquals(AgentLoop.Result.NeedsUser("which chat?"), result)
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a planner that never makes progress is stopped`() = runTest {
        val endless = object : Planner {
            override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>) = AgentAction.Tap(1)
        }

        val (agent, _) = loop(endless)
        val result = agent.run(Goal("spin forever", stepBudget = 10))

        assertTrue(result is AgentLoop.Result.Failed)
        assertTrue((result as AgentLoop.Result.Failed).reason.contains("changed nothing"))
    }

    @Test
    fun `a screen that can neither be listed nor captured stops the run`() = runTest {
        val refusingPlanner = object : Planner {
            override suspend fun next(goal: Goal, state: ScreenState, history: List<Step>): AgentAction =
                throw AssertionError("nothing describes this screen, asking is inventing")
        }

        // A game behind a security solution: no elements to name, and every
        // capture comes back flat so the observer discards it
        val (agent, _) = loop(refusingPlanner, screens = listOf(screen(elements = emptyList())))
        val result = agent.run(Goal("collect the daily reward"))

        assertTrue(result is AgentLoop.Result.Failed)
        assertTrue((result as AgentLoop.Result.Failed).reason.contains("cannot be listed or captured"))
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a point is not pressed once another app has come to the front`() = runTest {
        val game = screen(
            packageName = "com.blackdust.redblue",
            elements = listOf(
                element(0, "Game view", role = Role.SURFACE, clickable = false)
            )
        )
        // The planner thinks for a minute, and a purchase sheet arrives while
        // it does. The package rules were applied to the game, not to this
        val store = screen(
            packageName = "com.android.vending",
            hash = "store",
            elements = listOf(element(0, "구매"), element(1, "취소"))
        )

        val (agent, _) = loop(
            scriptedPlanner(AgentAction.TapAt(GridPoint(500, 900)), AgentAction.Done("done")),
            screens = listOf(game, store)
        )
        agent.run(Goal("collect the daily reward"))

        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a point is not pressed after the display has turned`() = runTest {
        val portrait = screen(
            packageName = "com.blackdust.redblue",
            elements = listOf(element(0, "Game view", role = Role.SURFACE, clickable = false)),
            displayWidth = 1080,
            displayHeight = 2340
        )
        // A game showing its splash portrait and then forcing landscape moves
        // neither hash, and the grid it was aimed on is no longer the display
        val landscape = portrait.copy(displayWidth = 2340, displayHeight = 1080)

        val (agent, _) = loop(
            scriptedPlanner(AgentAction.TapAt(GridPoint(500, 900)), AgentAction.Done("done")),
            screens = listOf(portrait, landscape)
        )
        agent.run(Goal("collect the daily reward"))

        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a failing run is not stored as a path`() = runTest {
        val (agent, store) = loop(scriptedPlanner(AgentAction.Tap(0), AgentAction.Fail("dead end")))

        agent.run(Goal("open settings"))

        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `replay still passes through the policy`() = runTest {
        val bank = screen(
            packageName = "viva.republica.toss",
            elements = listOf(element(0, "송금하기"), element(1, "취소"))
        )
        val store = newStore()
        store.save(
            storedTap(
                "send money", "송금하기", listOf("송금하기", "취소"),
                packageName = "viva.republica.toss"
            )
        )

        val (agent, _) = loop(scriptedPlanner(AgentAction.Done("nope")), store, screens = listOf(bank))
        val result = agent.run(Goal("send money"))

        assertTrue("got " + result, result is AgentLoop.Result.Blocked)
        assertTrue(performed.isEmpty())
        assertEquals(0, store.all().first { it.id == "t1" }.failureCount)
    }
}
