package com.hermes.agent.ui.onboarding

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    private fun mockSettings(): SettingsRepository = mockk(relaxed = true).also {
        coEvery { it.isOnboardingCompleted() } returns false
        coEvery { it.observe() } returns flowOf(UserSettings())
        coEvery { it.current() } returns UserSettings()
    }

    @Test
    fun `initial step is WELCOME`() = runTest {
        val vm = OnboardingViewModel(mockSettings())
        assertEquals(OnboardingViewModel.WELCOME, vm.step.value)
    }

    @Test
    fun `next advances step`() = runTest {
        val vm = OnboardingViewModel(mockSettings())
        vm.next()
        assertEquals(OnboardingViewModel.PRIVACY, vm.step.value)
        vm.next()
        assertEquals(OnboardingViewModel.PERMISSIONS, vm.step.value)
    }

    @Test
    fun `next caps at LAST_STEP`() = runTest {
        val vm = OnboardingViewModel(mockSettings())
        repeat(5) { vm.next() }
        assertEquals(OnboardingViewModel.LAST_STEP, vm.step.value)
    }

    @Test
    fun `back decreases step but not below zero`() = runTest {
        val vm = OnboardingViewModel(mockSettings())
        vm.next()
        vm.next()
        vm.back()
        assertEquals(OnboardingViewModel.PRIVACY, vm.step.value)
        vm.back()
        vm.back()
        assertEquals(OnboardingViewModel.WELCOME, vm.step.value)
    }

    @Test
    fun `complete persists onboarding_completed and sets completed flow`() = runTest {
        val settings = mockSettings()
        val vm = OnboardingViewModel(settings)
        assertFalse(vm.completed.value)

        vm.complete()
        advanceUntilIdle()

        assertTrue(vm.completed.value)
        coVerify { settings.setOnboardingCompleted(true) }
    }

    @Test
    fun `skip also completes onboarding`() = runTest {
        val settings = mockSettings()
        val vm = OnboardingViewModel(settings)

        vm.skip()
        advanceUntilIdle()

        assertTrue(vm.completed.value)
        coVerify { settings.setOnboardingCompleted(true) }
    }
}
