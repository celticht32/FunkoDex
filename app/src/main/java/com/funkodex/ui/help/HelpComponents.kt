package com.funkodex.ui.help

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * HelpComponents — reusable help UI composables.
 *
 * Three variants:
 *
 *  HelpBanner      — dismissible info bar. Used for one-time contextual hints
 *                    (scanner idle state, first-launch tips).
 *
 *  HelpCard        — persistent informational card inside a section. Used
 *                    for settings explanations that should always be visible.
 *
 *  HelpEmptyState  — full empty-state illustration with title + body + optional
 *                    CTA button. Used when a list or screen has no content.
 */

// ── HelpBanner ────────────────────────────────────────────────────────────────

/**
 * Dismissible info banner. The dismissed state is remembered for the
 * lifetime of the composition — on next app launch it shows again.
 * For permanent dismissal, hoist the state to a ViewModel or DataStore.
 */
@Composable
fun HelpBanner(
    text:      String,
    modifier:  Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }

    AnimatedVisibility(
        visible = visible,
        enter   = fadeIn() + expandVertically(),
        exit    = fadeOut() + shrinkVertically(),
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).padding(top = 1.dp),
                    tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text     = text,
                    modifier = Modifier.weight(1f),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                IconButton(
                    onClick  = { visible = false },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(14.dp),
                        tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

// ── HelpCard ──────────────────────────────────────────────────────────────────

/**
 * Persistent informational card. Always visible — no dismiss button.
 * Used inside settings sections and detail pages where the explanation
 * should always be available.
 */
@Composable
fun HelpCard(
    text:     String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp).padding(top = 2.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text  = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── HelpEmptyState ────────────────────────────────────────────────────────────

/**
 * Full empty-state display with icon, title, body text, and an optional
 * primary action button. Used when a list or screen has no content yet.
 */
@Composable
fun HelpEmptyState(
    icon:        androidx.compose.ui.graphics.vector.ImageVector,
    title:       String,
    body:        String,
    modifier:    Modifier = Modifier,
    actionLabel: String?  = null,
    onAction:    (() -> Unit)? = null,
) {
    Column(
        modifier            = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            text       = title,
            style      = MaterialTheme.typography.titleMedium,
            color      = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text      = body,
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
