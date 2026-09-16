package dev.droidpilot

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.agent.AgentLoop
import dev.droidpilot.core.model.AgentAction
import dev.droidpilot.core.model.Executor
import dev.droidpilot.core.model.Goal
import dev.droidpilot.core.model.InstalledApp
import dev.droidpilot.core.model.Role
import dev.droidpilot.core.model.ScreenState
import dev.droidpilot.planner.LlamaServerPlanner
import dev.droidpilot.policy.LoopGuard
import dev.droidpilot.policy.SafetyPolicy
import dev.droidpilot.trajectory.FileTrajectoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// The whole agent against the real model, on screens taken from a real phone,
// without the phone.
//
// Eight builds went out in one day because the only way to find out whether a
// change worked was to hand someone an apk and wait. Every one of those
// failures - the planner typing the goal into our own text box, a prompt too
// long for the model to read, an answer written to a field nobody read - would
// have shown up here in seconds.
//
// Skipped when no model server is reachable, so an ordinary build does not
// depend on one being up. runBlocking rather than runTest: the test dispatcher
// runs on virtual time, so every timeout in the loop fires at once while the
// real request is still on the wire.
@RunWith(AndroidJUnit4::class)
class LiveAgentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val performed = mutableListOf<AgentAction>()

    private val apps = listOf(
        InstalledApp("KakaoTalk", "com.kakao.talk"),
        InstalledApp("시계", "com.sec.android.app.clockpackage"),
        InstalledApp("카메라", "com.sec.android.app.camera"),
        InstalledApp("설정", "com.android.settings")
    ) + (0 until 40).map { InstalledApp("App " + it, "com.vendor.filler" + it) }

    // What pressing Run actually leaves in front of the agent
    private val ownScreen = screen(
        packageName = OWN_PACKAGE,
        activity = "MainActivity",
        elements = listOf(
            element(0, "goal", role = Role.INPUT, editable = true),
            element(1, "Run"),
            element(2, "Dump screen in 5s"),
            element(3, "Clear")
        )
    )

    // The chat list, as com.kakao.talk exposes it
    private val chatList = screen(
        packageName = "com.kakao.talk",
        activity = "MainActivity",
        hash = "list",
        elements = listOf(
            element(0, "검색"),
            element(1, "예니", left = 0, top = 300, right = 1080, bottom = 460),
            element(2, "동아리", left = 0, top = 460, right = 1080, bottom = 620),
            element(3, "가족", left = 0, top = 620, right = 1080, bottom = 780)
        )
    )

    private fun serverIsUp(): Boolean = runCatching {
        val connection = URL(PLANNER + "/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 1500
        connection.readTimeout = 1500
        connection.responseCode == 200
    }.getOrDefault(false)

    private fun loop(screens: () -> ScreenState): AgentLoop {
        val executor = object : Executor {
            override suspend fun perform(action: AgentAction, state: ScreenState): Result<Unit> {
                performed += action
                return Result.success(Unit)
            }
        }

        return AgentLoop(
            planner = LlamaServerPlanner(
                baseUrl = PLANNER,
                apps = { apps },
                ownPackage = OWN_PACKAGE
            ),
            executor = executor,
            policy = SafetyPolicy(ownPackage = OWN_PACKAGE),
            store = FileTrajectoryStore(File(temp.newFolder(), "t.json")),
            guardFactory = { budget -> LoopGuard(ApplicationProvider.getApplicationContext(), budget) },
            observe = { screens() },
            confirm = { true },
            settleMillis = 0L
        )
    }

    @Test
    fun `from our own screen the agent opens the app the goal names`() = runBlocking {
        assumeTrue("no model server on " + PLANNER, serverIsUp())

        // The screen only moves once something has been launched, which is what
        // the real phone does and what the broken version never achieved
        var launched = false
        val agent = loop { if (launched) chatList else ownScreen }

        val result = agent.run(Goal("카카오톡에서 예니에게 이따 연락할게 보내줘", stepBudget = 4))
        launched = performed.any { it is AgentAction.Launch }

        assertTrue(
            "expected a launch, got " + performed + " and " + result,
            performed.any { it is AgentAction.Launch && it.packageName.startsWith("com.kakao") }
        )
    }

    @Test
    fun `our own text box is never typed into`() = runBlocking {
        assumeTrue("no model server on " + PLANNER, serverIsUp())

        val agent = loop { ownScreen }
        agent.run(Goal("카카오톡에서 예니에게 이따 연락할게 보내줘", stepBudget = 4))

        // This is what eight releases in a day were chasing: the planner read
        // our goal box as somewhere to put the goal, and the run went nowhere
        assertTrue(
            "the agent operated its own interface: " + performed,
            performed.none { it is AgentAction.Input || it is AgentAction.Tap }
        )
    }

    @Test
    fun `an alarm goal opens the clock rather than the chat app`() = runBlocking {
        assumeTrue("no model server on " + PLANNER, serverIsUp())

        val agent = loop { ownScreen }
        agent.run(Goal("7시에 알람 맞춰줘", stepBudget = 3))

        val opened = performed.filterIsInstance<AgentAction.Launch>().map { it.packageName }
        assertTrue("expected the clock, got " + opened, opened.any { it.contains("clock") })
    }

    @Test
    fun `inside the chat app it picks the named conversation`() = runBlocking {
        assumeTrue("no model server on " + PLANNER, serverIsUp())

        val agent = loop { chatList }
        agent.run(Goal("예니에게 이따 연락할게 보내줘", stepBudget = 3))

        val tapped = performed.filterIsInstance<AgentAction.Tap>().map { it.elementId }
        assertTrue("expected the 예니 row (1), got " + tapped + " from " + performed, tapped.contains(1))
    }

    private companion object {
        const val OWN_PACKAGE = "dev.droidpilot"

        // The same server the phone talks to, so a pass here means the model
        // in front of the phone made these choices
        val PLANNER: String = System.getenv("DROIDPILOT_PLANNER")
            ?: "http://100.123.217.82:18080"
    }
}
