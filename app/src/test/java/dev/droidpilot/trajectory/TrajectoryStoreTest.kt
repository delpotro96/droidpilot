package dev.droidpilot.trajectory

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.core.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrajectoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): Pair<FileTrajectoryStore, File> {
        val file = File(temp.newFolder(), "trajectories.json")
        return FileTrajectoryStore(file) to file
    }

    private fun trajectory(id: String, goal: String) = Trajectory(
        id = id,
        goal = goal,
        steps = listOf(
            RecordedStep(
                action = RecordedAction.Tap(ElementRef("Send", Role.BUTTON, 0, 0, 100, 50)),
                signature = ScreenSignature("com.example.app", "MainActivity", listOf("Send"))
            )
        ),
        recordedAt = 1_000L
    )

    @Test
    fun `a saved path survives a round trip through the file`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))

        val loaded = store.findFor("send a message")
        assertNotNull(loaded)
        assertEquals("a", loaded?.id)
        assertEquals(1, loaded?.steps?.size)
    }

    @Test
    fun `goals match regardless of case and surrounding space`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "Send A Message"))

        assertNotNull(store.findFor("  send a message  "))
    }

    @Test
    fun `saving the same goal replaces the previous path`() = runTest {
        val (store, _) = store()
        store.save(trajectory("old", "send a message"))
        store.save(trajectory("new", "send a message"))

        assertEquals(1, store.all().size)
        assertEquals("new", store.findFor("send a message")?.id)
    }

    @Test
    fun `a single divergence does not retire a path`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))

        store.recordOutcome("a", RunOutcome.FAILED)

        // One popup is not evidence the path is wrong
        assertNotNull(store.findFor("send a message"))
    }

    @Test
    fun `a path that keeps failing stops being offered`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))

        repeat(Trajectory.MAX_FAILURES) { store.recordOutcome("a", RunOutcome.FAILED) }

        assertNull(store.findFor("send a message"))
        // It is still on disk, only untrusted
        assertEquals(1, store.all().size)
    }

    @Test
    fun `a path never seen to do anything retires on that alone`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))

        // Ran to the end every time without the screen changing, which is not
        // a failure but is not evidence of working either
        repeat(Trajectory.MAX_UNVERIFIED) { store.recordOutcome("a", RunOutcome.UNVERIFIED) }

        assertNull(store.findFor("send a message"))
    }

    @Test
    fun `a path that has worked once tolerates unverified runs`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))

        store.recordOutcome("a", RunOutcome.WORKED)
        repeat(5) { store.recordOutcome("a", RunOutcome.UNVERIFIED) }

        // Toggling a switch leaves no trace in the view tree
        assertNotNull(store.findFor("send a message"))
    }

    @Test
    fun `a proven path survives the odd failure`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))

        repeat(5) { store.recordOutcome("a", RunOutcome.WORKED) }
        repeat(3) { store.recordOutcome("a", RunOutcome.FAILED) }

        assertNotNull(store.findFor("send a message"))
    }

    @Test
    fun `an unchanged path keeps its record when saved again`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))
        repeat(4) { store.recordOutcome("a", RunOutcome.WORKED) }

        store.save(trajectory("b", "send a message"))

        assertEquals(4, store.findFor("send a message")?.successCount)
    }

    @Test
    fun `outcomes accumulate on the right path`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "goal one"))
        store.save(trajectory("b", "goal two"))

        store.recordOutcome("a", RunOutcome.WORKED)
        store.recordOutcome("a", RunOutcome.WORKED)
        store.recordOutcome("b", RunOutcome.FAILED)

        assertEquals(2, store.findFor("goal one")?.successCount)
        assertEquals(1, store.all().first { it.id == "b" }.failureCount)
    }

    @Test
    fun `a corrupted file costs the paths but does not throw`() = runTest {
        val (store, file) = store()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")

        assertTrue(store.all().isEmpty())
        assertNull(store.findFor("anything"))
    }

    @Test
    fun `an unknown goal returns nothing`() = runTest {
        val (store, _) = store()
        store.save(trajectory("a", "send a message"))

        assertNull(store.findFor("order a pizza"))
    }
}
