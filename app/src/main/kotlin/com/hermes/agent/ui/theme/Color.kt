package com.hermes.agent.ui.theme

import androidx.compose.ui.graphics.Color

// ╔══════════════════════════════════════════════════════════════════╗
// ║  Hermes Agent design system (Nous design — Geist + periwinkle).   ║
// ║  Signature accent #5b73ff on near-black; light mode uses #0000f2. ║
// ╚══════════════════════════════════════════════════════════════════╝

// Shared brand accents (theme-independent signature colors)
val HermesAccentBlue   = Color(0xFF5B73FF)  // --accent (dark)
val HermesAccentDeep   = Color(0xFF3A4BFF)  // --accent-deep
val HermesAccentInk    = Color(0xFFC9D2FF)  // --accent-ink
val HermesAccentPure   = Color(0xFF0000F2)  // --accent (light)
val HermesGood         = Color(0xFF46D399)  // --good
val HermesGoodLight    = Color(0xFF1F9D63)
val HermesWarn         = Color(0xFFF0B13B)  // --warn
val HermesWarnLight    = Color(0xFFA86D0A)
val HermesTerminalBg   = Color(0xFF070710)  // --term
val HermesTerminalText = Color(0xFFAAB6FF)  // --term-tx

// ── Midnight theme — design "Dark" (near-black / periwinkle) ─────────
val MidnightBackground        = Color(0xFF0A0A0F)  // --bg
val MidnightSurface           = Color(0xFF15151D)  // --surface
val MidnightSurfaceVariant    = Color(0xFF1D1D27)  // --surface-2
val MidnightOnBackground      = Color(0xFFF3F3F6)  // --text
val MidnightOnSurface         = Color(0xFFF3F3F6)
val MidnightOnSurfaceVariant  = Color(0xFF9596A3)  // --dim
val MidnightOutline           = Color(0xFF65667A)  // --faint
val MidnightPrimary           = HermesAccentBlue
val MidnightOnPrimary         = Color(0xFFFFFFFF)
val MidnightPrimaryContainer  = Color(0xFF222B66)  // accent-soft, opaque
val MidnightOnPrimaryContainer= HermesAccentInk
val MidnightSecondary         = HermesGood
val MidnightOnSecondary       = Color(0xFF06210F)
val MidnightSecondaryContainer= Color(0xFF12331F)
val MidnightError             = Color(0xFFFF6B6B)

val MidnightUserBubble        = HermesAccentBlue
val MidnightUserBubbleText    = Color(0xFFFFFFFF)
val MidnightAssistantBubble   = Color(0xFF15151D)
val MidnightAssistantBubbleText = Color(0xFFF3F3F6)

// ── Paper theme — design "Light" (warm white / electric blue) ────────
val PaperBackground           = Color(0xFFF3F2EE)  // --bg
val PaperSurface              = Color(0xFFFFFFFF)   // --surface
val PaperSurfaceVariant       = Color(0xFFF0EFEA)   // --surface-2
val PaperOnBackground         = Color(0xFF0D0D12)   // --text
val PaperOnSurface            = Color(0xFF0D0D12)
val PaperOnSurfaceVariant     = Color(0xFF5D5E6A)   // --dim
val PaperOutline              = Color(0xFF9596A1)   // --faint
val PaperPrimary              = HermesAccentPure
val PaperOnPrimary            = Color(0xFFFFFFFF)
val PaperPrimaryContainer     = Color(0xFFE2E2FE)   // accent-soft, opaque
val PaperOnPrimaryContainer   = HermesAccentPure
val PaperSecondary            = HermesGoodLight
val PaperOnSecondary          = Color(0xFFFFFFFF)
val PaperSecondaryContainer   = Color(0xFFD7F0E2)
val PaperError                = Color(0xFFCC0000)

val PaperUserBubble           = HermesAccentPure
val PaperUserBubbleText       = Color(0xFFFFFFFF)
val PaperAssistantBubble      = Color(0xFFFFFFFF)
val PaperAssistantBubbleText  = Color(0xFF0D0D12)

// ── Hermes Blue theme (electric blue background / white text) ────────
// Brand blue sourced from hermes-agent.nousresearch.com
val BlueBrandBackground           = Color(0xFF3300FF)
val BlueBrandSurface              = Color(0xFF2200DD)
val BlueBrandSurfaceVariant       = Color(0xFF1A00BB)
val BlueBrandOnBackground         = Color(0xFFFFFFFF)
val BlueBrandOnSurface            = Color(0xFFFFFFFF)
val BlueBrandOnSurfaceVariant     = Color(0xFFCCCCFF)
val BlueBrandPrimary              = Color(0xFFFFFFFF)
val BlueBrandOnPrimary            = Color(0xFF3300FF)
val BlueBrandPrimaryContainer     = Color(0xFF4411FF)
val BlueBrandOnPrimaryContainer   = Color(0xFFFFFFFF)
val BlueBrandSecondary            = Color(0xFFCCCCFF)
val BlueBrandOnSecondary          = Color(0xFF3300FF)
val BlueBrandSecondaryContainer   = Color(0xFF2200CC)
val BlueBrandError                = Color(0xFFFF6666)

val BlueBrandUserBubble           = Color(0xFF1A00BB)
val BlueBrandUserBubbleText       = Color(0xFFFFFFFF)
val BlueBrandAssistantBubble      = Color(0xFF2200CC)
val BlueBrandAssistantBubbleText  = Color(0xFFFFFFFF)

// ── Legacy palette (kept for any composables still referencing these) ─
val HermesPrimary              = Color(0xFF1E3A8A)
val HermesPrimaryDark          = Color(0xFF172554)
val HermesPrimaryContainer     = Color(0xFFDDE6FF)
val HermesOnPrimary            = Color(0xFFFFFFFF)
val HermesOnPrimaryContainer   = Color(0xFF001550)
val HermesAccent               = Color(0xFFF59E0B)
val HermesAccentDark           = Color(0xFFFFB951)
val HermesAccentContainer      = Color(0xFFFFE9C7)
val HermesOnAccent             = Color(0xFF1A1300)
val HermesBackground           = Color(0xFFF8FAFC)
val HermesSurface              = Color(0xFFFFFFFF)
val HermesSurfaceVariant       = Color(0xFFE7EAF0)
val HermesOnBackground         = Color(0xFF0F172A)
val HermesOnSurface            = Color(0xFF0F172A)
val HermesOnSurfaceVariant     = Color(0xFF44474F)
val HermesSuccess              = Color(0xFF16A34A)
val HermesWarning              = Color(0xFFEA580C)
val HermesError                = Color(0xFFDC2626)
val HermesPrimaryDarkMode      = Color(0xFFB1C5FF)
val HermesAccentDarkMode       = Color(0xFFFFB951)
val HermesBackgroundDark       = Color(0xFF0F172A)
val HermesSurfaceDark          = Color(0xFF1E293B)
val HermesOnBackgroundDark     = Color(0xFFE2E8F0)
val HermesOnSurfaceDark        = Color(0xFFE2E8F0)
val UserBubbleColor            = Color(0xFF1E3A8A)
val UserBubbleTextColor        = Color(0xFFFFFFFF)
val AssistantBubbleColor       = Color(0xFFF1F5F9)
val AssistantBubbleTextColor   = Color(0xFF0F172A)
val AssistantBubbleColorDark   = Color(0xFF334155)
val AssistantBubbleTextColorDark = Color(0xFFE2E8F0)
