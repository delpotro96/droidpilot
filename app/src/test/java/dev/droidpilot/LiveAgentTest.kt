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

    // A conversation, laid out the way the real dump came back: every message
    // bubble is its own clickable button, the compose bar is last, and the send
    // button only exists once something has been typed
    private fun chatRoom(typed: String? = null) = screen(
        packageName = "com.kakao.talk",
        activity = "ChatRoomActivity",
        hash = "room" + (typed ?: ""),
        elements = listOfNotNull(
            element(0, "뒤로", left = 0, top = 0, right = 120, bottom = 120),
            element(1, "예니", role = Role.TEXT, clickable = false, left = 140, top = 0, right = 700, bottom = 120),
            element(2, "검색", left = 800, top = 0, right = 920, bottom = 120),
            element(3, "오늘 뭐해?", left = 100, top = 300, right = 900, bottom = 380),
            element(4, "ㅋㅋㅋ", left = 100, top = 400, right = 900, bottom = 480),
            element(5, typed, role = Role.INPUT, editable = true, left = 0, top = 2100, right = 900, bottom = 2200),
            typed?.let { element(6, "전송", left = 900, top = 2100, right = 1080, bottom = 2200) }
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

    @Test
    fun `in a conversation it types the message rather than pressing a bubble`() = runBlocking {
        assumeTrue("no model server on " + PLANNER, serverIsUp())

        val agent = loop { chatRoom() }
        agent.run(Goal("예니에게 '이따 연락할게' 라고 보내줘", stepBudget = 3))

        val typed = performed.filterIsInstance<AgentAction.Input>()
        assertTrue("expected typing, got " + performed, typed.isNotEmpty())

        // The compose bar, not one of the message bubbles above it
        assertTrue("typed into element " + typed.first().elementId, typed.first().elementId == 5)
    }

    @Test
    fun `sending is declared irreversible and asks before it goes`() = runBlocking {
        assumeTrue("no model server on " + PLANNER, serverIsUp())

        val asked = mutableListOf<String>()
        val agent = loopAsking(asked) { chatRoom(typed = "이따 연락할게") }
        agent.run(Goal("예니에게 '이따 연락할게' 라고 보내줘", stepBudget = 3))

        // Whether the planner declares it or the keyword net catches it, the
        // one thing that must not happen is a message leaving without a word
        val sent = performed.filterIsInstance<AgentAction.Tap>().any { it.elementId == 6 }
        assertTrue(
            "send was performed with no question asked: " + performed,
            !sent || asked.isNotEmpty()
        )
    }

    private fun loopAsking(asked: MutableList<String>, screens: () -> ScreenState): AgentLoop {
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
            confirm = { reason -> asked += reason; true },
            settleMillis = 0L
        )
    }

    private companion object {
        const val OWN_PACKAGE = "dev.droidpilot"

        // The same server the phone talks to, so a pass here means the model
        // in front of the phone made these choices
        val PLANNER: String = System.getenv("DROIDPILOT_PLANNER")
            ?: "http://100.123.217.82:18080"
    }
}
