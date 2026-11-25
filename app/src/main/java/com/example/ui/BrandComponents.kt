package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.LocChatBrandGradient
import com.example.ui.theme.LocChatRimBorderDark
import com.example.ui.theme.LocChatRimBorderLight
import com.example.ui.theme.MeshAzure
import com.example.ui.theme.MeshViolet
import com.example.ui.theme.NearbyCyanTeal
import com.example.ui.theme.PrivateShieldGold

/** Hairline outline used by cards, inputs and media (flat feed look). */
@Composable
fun locChatRimBorder(): BorderStroke {
    return BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
}

/** Thin full-width divider between feed rows and around bars. */
@Composable
fun FeedDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

/**
 * Round avatar with the person's initial (or a # glyph for channels).
 * With [ring] = true it gets the brand-gradient "story" ring.
 */
@Composable
fun LocChatAvatar(
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    isChannel: Boolean = false,
    ring: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val initial = label.trim().trimStart('#').take(1).uppercase().ifEmpty { "?" }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .then(if (ring) Modifier.border(2.5.dp, LocChatBrandGradient, CircleShape) else Modifier)
            .padding(if (ring) 4.dp else 0.dp)
            .clip(CircleShape)
            .then(
                if (isChannel) Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                else Modifier.background(LocChatBrandGradient)
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        if (isChannel) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.45f)
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp
            )
        }
    }
}

@Composable
fun LocChatSquircleLogo(
    size: Dp,
    showAmbientGlow: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cornerRadius = size * 0.24f
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(
                if (showAmbientGlow) {
                    Modifier.drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    ElectricCyan.copy(alpha = 0.24f),
                                    MeshViolet.copy(alpha = 0.16f),
                                    Color.Transparent
                                )
                            ),
                            radius = this.size.maxDimension * 0.72f
                        )
                    }
                } else Modifier
            )
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_locchat_logo),
            contentDescription = "LocChat",
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .border(
                    width = 1.dp,
                    brush = LocChatRimBorderDark,
                    shape = RoundedCornerShape(cornerRadius)
                )
        )
    }
}

@Composable
fun LocChatWordmark(
    fontSize: TextUnit = 21.sp,
    modifier: Modifier = Modifier
) {
    val locColor = MaterialTheme.colorScheme.onSurface
    val styled = buildAnnotatedString {
        withStyle(SpanStyle(color = locColor, fontWeight = FontWeight.ExtraBold)) {
            append("Loc")
        }
        withStyle(
            SpanStyle(
                brush = LocChatBrandGradient,
                fontWeight = FontWeight.ExtraBold
            )
        ) {
            append("Chat")
        }
    }
    Text(
        text = styled,
        style = TextStyle(
            fontSize = fontSize,
            letterSpacing = (-0.3).sp
        ),
        modifier = modifier
    )
}

@Composable
fun LocChatTaglineDivider(
    text: String = "CONNECT LOCALLY • تواصل محلي",
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, ElectricCyan.copy(alpha = 0.7f))
                    )
                )
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(MeshViolet.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
        )
    }
}

@Composable
fun LocChatFeaturePillRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FeatureMiniPill(Icons.Default.Bluetooth, "بلوتوث", MeshAzure)
        FeatureMiniPill(Icons.Default.Forum, "محادثات", MeshViolet)
        FeatureMiniPill(Icons.Default.Groups, "قريب", NearbyCyanTeal)
        FeatureMiniPill(Icons.Default.Shield, "خاص", PrivateShieldGold)
    }
}

@Composable
private fun FeatureMiniPill(
    icon: ImageVector,
    label: String,
    accent: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = accent.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
