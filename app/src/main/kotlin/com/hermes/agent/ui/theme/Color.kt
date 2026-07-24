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

// ── Midnight theme — deep slate / indigo undertone ─────────────────────
val MidnightBackground        = Color(0xFF0F111A)
val MidnightSurface           = Color(0xFF161925)
val MidnightSurfaceVariant    = Color(0xFF1E2233)
val MidnightOnBackground      = Color(0xFFF1F2F6)
val MidnightOnSurface         = Color(0xFFF1F2F6)
val MidnightOnSurfaceVariant  = Color(0xFFA1A5B7)
val MidnightOutline           = Color(0xFF32364A)
val MidnightPrimary           = Color(0xFF64B5F6)
val MidnightOnPrimary         = Color(0xFF000000)
val MidnightPrimaryContainer  = Color(0xFF193B59)
val MidnightOnPrimaryContainer= Color(0xFF64B5F6)
val MidnightSecondary         = Color(0xFFE2E2E2)
val MidnightOnSecondary       = Color(0xFF000000)
val MidnightSecondaryContainer= Color(0xFF2A2D3D)
val MidnightError             = Color(0xFFFF5252)

val MidnightUserBubble        = Color(0xFF193B59)
val MidnightUserBubbleText    = Color(0xFFF1F2F6)
val MidnightAssistantBubble   = Color(0xFF1E2233)
val MidnightAssistantBubbleText = Color(0xFFF1F2F6)

// ── Paper theme — warm sleek off-white ─────────────────────────────────
val PaperBackground           = Color(0xFFF8F9FA)
val PaperSurface              = Color(0xFFFFFFFF)
val PaperSurfaceVariant       = Color(0xFFF1F3F5)
val PaperOnBackground         = Color(0xFF1A1C23)
val PaperOnSurface            = Color(0xFF1A1C23)
val PaperOnSurfaceVariant     = Color(0xFF6C7280)
val PaperOutline              = Color(0xFFDEE2E6)
val PaperPrimary              = Color(0xFF5C6BC0)
val PaperOnPrimary            = Color(0xFFFFFFFF)
val PaperPrimaryContainer     = Color(0xFFE8EAF6)
val PaperOnPrimaryContainer   = Color(0xFF5C6BC0)
val PaperSecondary            = Color(0xFF2E7D32)
val PaperOnSecondary          = Color(0xFFFFFFFF)
val PaperSecondaryContainer   = Color(0xFFC8E6C9)
val PaperError                = Color(0xFFD32F2F)

val PaperUserBubble           = Color(0xFFE8EAF6)
val PaperUserBubbleText       = Color(0xFF1A1C23)
val PaperAssistantBubble      = Color(0xFFFFFFFF)
val PaperAssistantBubbleText  = Color(0xFF1A1C23)

// ── Hermes Blue theme — deep blue accent brand ──────────────────────────
val BlueBrandBackground           = Color(0xFF1A00BB)
val BlueBrandSurface              = Color(0xFF2200CC)
val BlueBrandSurfaceVariant       = Color(0xFF2A00DD)
val BlueBrandOnBackground         = Color(0xFFFFFFFF)
val BlueBrandOnSurface            = Color(0xFFFFFFFF)
val BlueBrandOnSurfaceVariant     = Color(0xFFCCCCFF)
val BlueBrandPrimary              = Color(0xFFFFFFFF)
val BlueBrandOnPrimary            = Color(0xFF1A00BB)
val BlueBrandPrimaryContainer     = Color(0xFF3300FF)
val BlueBrandOnPrimaryContainer   = Color(0xFFFFFFFF)
val BlueBrandSecondary            = Color(0xFFCCCCFF)
val BlueBrandOnSecondary          = Color(0xFF1A00BB)
val BlueBrandSecondaryContainer   = Color(0xFF2200CC)
val BlueBrandError                = Color(0xFFFF6666)

val BlueBrandUserBubble           = Color(0xFF3300FF)
val BlueBrandUserBubbleText       = Color(0xFFFFFFFF)
val BlueBrandAssistantBubble      = Color(0xFF2200CC)
val BlueBrandAssistantBubbleText  = Color(0xFFFFFFFF)

// ── Kanban board status + priority colors (ported from Hermes App 2) ──
val KanbanTodo        = Color(0xFF707070)
val KanbanInProgress  = Color(0xFFF0F0F0)
val KanbanReview      = Color(0xFFBDBDBD)
val KanbanBlocked     = Color(0xFF4A4A4A)
val KanbanDone        = Color(0xFFFFFFFF)
val KanbanCancelled   = Color(0xFF333333)

val PriorityCritical  = Color(0xFFFFFFFF)
val PriorityHigh      = Color(0xFFD0D0D0)
val PriorityMedium    = Color(0xFF9A9A9A)
val PriorityLow       = Color(0xFF666666)

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
