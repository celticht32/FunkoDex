package com.funkodex.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.HelpBanner(
        text     = HelpContent.CATEGORY_FILTER,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.help.HelpBanner
import com.funkodex.ui.help.HelpContent
import com.funkodex.data.model.CategoryPreference
import com.funkodex.data.model.FunkoGenre

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFilterScreen(
    onBack: () -> Unit,
    viewModel: CategoryFilterViewModel = hiltViewModel(),
) {
    val grouped  by viewModel.grouped.collectAsState()
    val counts   by viewModel.genreCounts.collectAsState()
    val expanded = remember { mutableStateMapOf<FunkoGenre, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My collection categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::resetToDefaults) { Text("Reset") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier        = Modifier.padding(padding).fillMaxSize(),
            contentPadding  = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "Enabled categories affect catalog search, series completion, and want list suggestions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Items you already own always show, regardless of category settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            grouped.forEach { (genre, categories) ->
                val isExpanded  = expanded[genre] ?: false
                val enabledInGroup = categories.count { it.isEnabled }
                val total = categories.size

                item(key = genre.name) {
                    GenreHeader(
                        genre       = genre,
                        enabledCount= enabledInGroup,
                        total       = total,
                        isExpanded  = isExpanded,
                        onToggleAll = { viewModel.setGenreEnabled(genre, enabledInGroup < total) },
                        onExpand    = { expanded[genre] = !isExpanded }
                    )
                }

                if (isExpanded) {
                    items(categories, key = { it.categoryKey }) { pref ->
                        CategoryRow(
                            pref     = pref,
                            onToggle = { viewModel.setEnabled(pref.categoryKey, !pref.isEnabled) }
                        )
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun GenreHeader(
    genre: FunkoGenre,
    enabledCount: Int,
    total: Int,
    isExpanded: Boolean,
    onToggleAll: () -> Unit,
    onExpand: () -> Unit,
) {
    val allEnabled = enabledCount == total
    val noneEnabled = enabledCount == 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = if (allEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Genre icon
            Icon(
                when (genre) {
                    FunkoGenre.ENTERTAINMENT -> Icons.Default.Movie
                    FunkoGenre.MUSIC         -> Icons.Default.MusicNote
                    FunkoGenre.SPORTS        -> Icons.Default.SportsSoccer
                    FunkoGenre.ICONS         -> Icons.Default.Star
                    FunkoGenre.FANTASY_MYTH  -> Icons.Default.AutoAwesome
                    FunkoGenre.LIFESTYLE     -> Icons.Default.Favorite
                    FunkoGenre.MILITARY      -> Icons.Default.Shield
                    FunkoGenre.OTHER         -> Icons.Default.Category
                },
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(genre.displayName, fontWeight = FontWeight.Medium)
                Text(
                    "$enabledCount of $total categories enabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Genre-level toggle (all on / all off)
            IconButton(
                onClick  = onToggleAll,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (allEnabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                    contentDescription = if (allEnabled) "Disable all" else "Enable all",
                    tint     = if (allEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }

            Icon(
                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CategoryRow(
    pref: CategoryPreference,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent   = {
            Text(
                pref.categoryName,
                fontWeight = if (pref.isEnabled) FontWeight.Medium else FontWeight.Normal,
                color      = if (pref.isEnabled) MaterialTheme.colorScheme.onSurface
                             else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent   = {
            Switch(
                checked         = pref.isEnabled,
                onCheckedChange = { onToggle() },
            )
        },
        modifier          = Modifier
            .clickable(onClick = onToggle)
            .padding(start = 16.dp),
        colors            = ListItemDefaults.colors(
            containerColor = if (pref.isEnabled) MaterialTheme.colorScheme.surface
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    )
}
