package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand Core — Extracted from LocChat Icon & Poster
val ElectricCyan = Color(0xFF33DFFF)
val ElectricCyanDark = Color(0xFF0077B6)
val ElectricCyanContainerDark = Color(0xFF0B2F44)
val ElectricCyanContainerLight = Color(0xFFD6F5FF)

val MeshAzure = Color(0xFF3B82F6)
val MeshIndigo = Color(0xFF5B8CFF)
val MeshIndigoDark = Color(0xFF3459B5)

val MeshViolet = Color(0xFF9B6CFF)
val MeshVioletDark = Color(0xFF6B3FD6)
val MeshVioletContainerDark = Color(0xFF251A48)
val MeshVioletContainerLight = Color(0xFFEDE5FF)

// 4-Pill Feature Accents (Bluetooth / Chat / Nearby / Private)
val NearbyCyanTeal = Color(0xFF14E3C9)
val PrivateShieldGold = Color(0xFFF5A623)
val RadarAmber = Color(0xFFF5A623)
val RadarAmberDark = Color(0xFFB26A00)

// Social-feed palette: flat black / white canvas with hairline dividers
// Primary action colour
val SignalBlue = Color(0xFF1D9BF0)
val SignalBlueContainerDark = Color(0xFF0D2B40)
val SignalBlueContainerLight = Color(0xFFE8F5FD)

// Surfaces — Dark (pure black canvas, raised surfaces for dialogs/inputs)
val SlateBgDark = Color(0xFF000000)
val SlateSurfaceDark = Color(0xFF16181C)
val SlateSurfaceVariantDark = Color(0xFF202327)
val SlateOutlineDark = Color(0xFF2F3336)

// Surfaces — Light
val MistBgLight = Color(0xFFFFFFFF)
val MistSurfaceLight = Color(0xFFFFFFFF)
val MistSurfaceVariantLight = Color(0xFFF1F4F5)
val MistOutlineLight = Color(0xFFE1E8EC)

// Text
val TextPrimaryDark = Color(0xFFE7E9EA)
val TextSecondaryDark = Color(0xFF71767B)
val TextPrimaryLight = Color(0xFF0F1419)
val TextSecondaryLight = Color(0xFF536471)

// Semantic Status
val VerifiedEmerald = Color(0xFF22C55E)
val PanicCrimson = Color(0xFFFF4D67)
val PanicCrimsonDark = Color(0xFF9E1B32)
val OfflineGray = Color(0xFF64748B)

// Signature LocChat Gradients
val LocChatBrandGradient = Brush.linearGradient(
    colors = listOf(ElectricCyan, MeshAzure, MeshViolet)
)

val LocChatRimBorderDark = Brush.linearGradient(
    colors = listOf(
        Color(0xFF56749E).copy(alpha = 0.55f),
        Color(0xFF23334C).copy(alpha = 0.40f)
    )
)

val LocChatRimBorderLight = Brush.linearGradient(
    colors = listOf(
        ElectricCyanDark.copy(alpha = 0.35f),
        MeshVioletDark.copy(alpha = 0.25f)
    )
)
