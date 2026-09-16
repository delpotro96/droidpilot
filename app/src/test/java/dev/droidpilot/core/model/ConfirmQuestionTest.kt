package dev.droidpilot.core.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.droidpilot.element
import dev.droidpilot.screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// The person is the judgement the model does not have, and only if they are
// told enough to use it
@RunWith(AndroidJUnit4::class)
class ConfirmQuestionTest {

    @Test
    fun `a send names the conversation it is going to`() {
        val state = screen(
            packageName = "com.kakao.talk",
            elements = listOf(
                element(0, "뒤로", left = 0, top = 0, right = 120, bottom = 120),
                element(1, "예니", role = Role.TEXT, clickable = false,
                    left = 140, top = 0, right = 700, bottom = 120),
                element(2, "이따 연락할게", left = 100, top = 900, right = 900, bottom = 980),
                element(3, "전송", left = 900, top = 2100, right = 1080, bottom = 2200)
            )
        )

        val question = ConfirmQuestion.of("cannot be undone: 전송", state)

        // Approving "cannot be undone: 전송" tells you a message is going and
        // not who to, which is the only part worth checking
        assertTrue(question, question.contains("예니"))
        assertTrue(question, question.contains("com.kakao.talk"))
        assertTrue(question, question.startsWith("cannot be undone: 전송"))
    }

    @Test
    fun `the top of the screen is what is quoted, not the first element listed`() {
        val state = screen(
            elements = listOf(
                element(0, "somewhere near the bottom", left = 0, top = 2000, right = 900, bottom = 2100),
                element(1, "Delete account", left = 0, top = 40, right = 900, bottom = 140)
            )
        )

        val question = ConfirmQuestion.of("cannot be undone: 삭제", state)

        assertTrue(question, question.contains("Delete account"))
    }

    @Test
    fun `a screen with nothing readable still says which app it is`() {
        val state = screen(
            packageName = "com.blackdust.redblue",
            elements = listOf(element(0, null, role = Role.SURFACE, clickable = false))
        )

        assertEquals(
            "the planner said this spends money\nin com.blackdust.redblue",
            ConfirmQuestion.of("the planner said this spends money", state)
        )
    }

    @Test
    fun `a message long enough to be a paragraph is cut`() {
        val state = screen(
            elements = listOf(element(0, "x".repeat(400), left = 0, top = 0, right = 900, bottom = 100))
        )

        assertTrue(ConfirmQuestion.of("cannot be undone: 전송", state).length < 200)
    }
}
