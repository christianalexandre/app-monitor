package appmonitor.sdk.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FirstSettledScreenTest {
    private class Screen(
        val name: String,
    ) {
        var finishing = false
    }

    /** A main thread with a manual clock. */
    private var now = 0L
    private val due = mutableListOf<Pair<Long, () -> Unit>>()
    private val asked = mutableListOf<String>()
    private val answered = mutableListOf<String>()

    private val first =
        FirstSettledScreen<Screen>(
            settleMs = 500,
            schedule = { delay, block ->
                val entry = (now + delay) to block
                due += entry
                { due.remove(entry) }
            },
            gone = { it.finishing },
            ask = { asked += it.name },
            answered = { answered += it.name },
        )

    private fun advance(ms: Long) {
        now += ms
        while (true) {
            val next = due.filter { it.first <= now }.minByOrNull { it.first } ?: return
            due.remove(next)
            next.second()
        }
    }

    @Test
    fun asksOnTheFirstScreenThatStaysResumed() {
        val main = Screen("main")
        first.resumed(main)
        advance(499)
        assertEquals(emptyList(), asked)
        advance(1)
        assertEquals(listOf("main"), asked)
    }

    @Test
    fun aTrampolineThatHandsOverSoonerIsSkipped() {
        val splash = Screen("splash")
        val main = Screen("main")
        first.resumed(splash)
        advance(100)
        first.paused(splash)
        first.resumed(main)
        advance(1_000)
        assertEquals(listOf("main"), asked)
    }

    @Test
    fun aScreenFinishedBeforeItSettlesIsSkipped() {
        val main = Screen("main")
        first.resumed(main)
        main.finishing = true
        advance(1_000)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun asksOncePerProcess() {
        val main = Screen("main")
        first.resumed(main)
        advance(500)
        first.paused(main)
        first.resumed(main)
        advance(1_000)
        assertEquals(listOf("main"), asked)
    }

    @Test
    fun twoScreensResumedTogetherAskOnceOnTheLast() {
        // Multi-window: both stay resumed, no pause in between.
        val left = Screen("left")
        val right = Screen("right")
        first.resumed(left)
        advance(100)
        first.resumed(right)
        advance(1_000)
        assertEquals(listOf("right"), asked)
    }

    @Test
    fun theScreenLeftResumedAsksWhenTheOtherPauses() {
        val left = Screen("left")
        val right = Screen("right")
        first.resumed(left)
        first.resumed(right)
        advance(100)
        first.paused(right)
        advance(1_000)
        assertEquals(listOf("left"), asked)
    }

    @Test
    fun theFirstScreenResumedAfterTheDialogIsAnsweredOnce() {
        val main = Screen("main")
        first.resumed(main)
        advance(500)
        assertEquals(emptyList(), answered)
        first.paused(main)
        first.resumed(main)
        first.paused(main)
        first.resumed(main)
        assertEquals(listOf("main"), answered)
    }
}
