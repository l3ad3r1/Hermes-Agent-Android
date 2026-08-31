package com.hermes.agent.ui.usage

import com.hermes.agent.data.repository.UsageInsightsRepository
import com.hermes.agent.domain.usage.UsageSummary
import com.hermes.agent.domain.usage.UsageTimeWindow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [UsageInsightsViewModel].
 *
 * The usage numbers used to be reachable only through the `usage_insights`
 * tool, i.e. you spent tokens to find out how many tokens you had spent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageInsightsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: UsageInsightsRepository

    private fun summary(window: String, tokens: Long = 0L) = UsageSummary(
        window = window,
        totalSessions = 1,
        totalMessages = 2,
        totalTokens = tokens,
        promptTokens = tokens / 2,
        completionTokens = tokens / 2,
        estimatedCostUsd = 0.5,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        coEvery { repository.getUsageSummary(any()) } returns summary("all")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `it opens on the last 7 days rather than all time`() = runTest(dispatcher) {
        val vm = UsageInsightsViewModel(repository)
        advanceUntilIdle()

        assertEquals(UsageTimeWindow.LAST_7_DAYS, vm.state.value.window)
        coVerify { repository.getUsageSummary(UsageTimeWindow.LAST_7_DAYS) }
        assertFalse(vm.state.value.isLoading)
        assertNotNull(vm.state.value.summary)
    }

    @Test
    fun `switching window reloads from the repository`() = runTest(dispatcher) {
        coEvery { repository.getUsageSummary(UsageTimeWindow.TODAY) } returns summary("today", 42L)
        val vm = UsageInsightsViewModel(repository)
        advanceUntilIdle()

        vm.load(UsageTimeWindow.TODAY)
        advanceUntilIdle()

        assertEquals(UsageTimeWindow.TODAY, vm.state.value.window)
        assertEquals(42L, vm.state.value.summary!!.totalTokens)
        coVerify { repository.getUsageSummary(UsageTimeWindow.TODAY) }
    }

    @Test
    fun `a repository failure surfaces as an error, not a spinner that never ends`() =
        runTest(dispatcher) {
            coEvery { repository.getUsageSummary(any()) } throws IllegalStateException("db closed")

            val vm = UsageInsightsViewModel(repository)
            advanceUntilIdle()

            assertFalse(vm.state.value.isLoading)
            assertEquals("db closed", vm.state.value.error)
            assertNull(vm.state.value.summary)
        }

    @Test
    fun `a retry after a failure clears the error`() = runTest(dispatcher) {
        coEvery { repository.getUsageSummary(any()) } throws IllegalStateException("db closed")
        val vm = UsageInsightsViewModel(repository)
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)

        coEvery { repository.getUsageSummary(any()) } returns summary("today", 7L)
        vm.load(UsageTimeWindow.TODAY)
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals(7L, vm.state.value.summary!!.totalTokens)
    }

    @Test
    fun `loading is shown while the query runs`() = runTest(dispatcher) {
        val vm = UsageInsightsViewModel(repository)

        // Before the dispatcher runs the coroutine, the state must already say
        // loading - otherwise the screen renders an empty summary for a frame.
        assertTrue(vm.state.value.isLoading)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
    }
}
