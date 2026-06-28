package com.hermes.agent.ui.onboarding

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    // viewModelScope dispatches on Main; install a test dispatcher for it.
    @Before fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDownMain() = Dispatchers.resetMain()

    private fun mockSettings(): SettingsRepository = mockk<SettingsRepository>(relaxed = true).also {
        coEvery { it.isOnboardingCompleted() } returns false
        val settingsFlow: Flow<UserSettings> = flowOf(UserSettings())
        coEvery { it.observe() } returns settingsFlow
        coEvery { it.current() } returns UserSettings()
    }

    /** Build a VM with the current 3-arg constructor; memory + profiler relaxed. */
    private fun vm(settings: SettingsRepository = mockSettings()) =
        OnboardingViewModel(settings, mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `initial step is WELCOME`() = runTest {
        assertEquals(OnboardingViewModel.WELCOME, vm().step.value)
    }

    @Test
    fun `next advances step`() = runTest {
        val viewModel = vm()
        viewModel.next()
        assertEquals(OnboardingViewModel.PROFILE, viewModel.step.value)
        viewModel.next()
        assertEquals(OnboardingViewModel.PERMISSIONS, viewModel.step.value)
    }

    @Test
    fun `next caps at DEVICE`() = runTest {
        val viewModel = vm()
        repeat(5) { viewModel.next() }
        assertEquals(OnboardingViewModel.DEVICE, viewModel.step.value)
    }

    @Test
    fun `back decreases step but not below zero`() = runTest {
        val viewModel = vm()
        viewModel.next()
        viewModel.next()
        viewModel.back()
        assertEquals(OnboardingViewModel.PROFILE, viewModel.step.value)
        viewModel.back()
        viewModel.back()
        assertEquals(OnboardingViewModel.WELCOME, viewModel.step.value)
    }

    @Test
    fun `finish persists onboarding_completed and sets completed flow`() = runTest {
        val settings = mockSettings()
        val viewModel = vm(settings)
        assertFalse(viewModel.completed.value)

        viewModel.finish()
        advanceUntilIdle()

        assertTrue(viewModel.completed.value)
        coVerify { settings.setOnboardingCompleted(true) }
    }

    @Test
    fun `skip also completes onboarding`() = runTest {
        val settings = mockSettings()
        val viewModel = vm(settings)

        viewModel.skip()
        advanceUntilIdle()

        assertTrue(viewModel.completed.value)
        coVerify { settings.setOnboardingCompleted(true) }
    }
}
