package com.funkodex.ui.screens.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.funkodex.data.export.ExportButton
import com.funkodex.data.model.CollectionStats
import com.funkodex.data.model.GroupIntent
import com.funkodex.data.model.GroupLevel
import com.funkodex.data.model.SeriesSummary
import com.funkodex.ui.help.HelpCard
import com.funkodex.ui.help.HelpContent
import com.funkodex.ui.help.HelpEmptyState
import java.text.NumberFormat
import java.util.Locale

private val currencyFmt: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

@Composable
fun ReportsScreen(
    onItemClick: (String) -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val stats = uiState.stats

    // ReportsViewModel computes stats once on creation. If the user refreshes
    // an item's price on the Detail screen (writing marketAvg) and navigates
    // back here, the cached ViewModel instance wouldn't otherwise recompute —
    // re-fetch on resume so Est. Market Value / totals reflect recent writes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        uiState.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        stats == null || (stats.totalOwned == 0 && stats.totalWanted == 0) -> {
            HelpEmptyState(
                icon  = Icons.Default.BarChart,
                title = "No data yet",
                body  = HelpContent.REPORTS_EMPTY,
            )
        }

        else -> {
            ReportsContent(
                stats = stats,
                onItemClick = onItemClick,
            )
        }
    }
}

@Composable
private fun ReportsContent(
    stats: CollectionStats,
    onItemClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Summary cards ───────────────────────────────────────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Summary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SummaryGrid(stats)
            }
        }

        // ── Cost breakdown ──────────────────────────────────────────────────
        item {
            CostBreakdownCard(stats)
        }

        // ── Export ──────────────────────────────────────────────────────────
        item {
            ExportButton()
        }

        // ── Series completion ──────────────────────────────────────────────
        item {
            Text("Series Completion", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (stats.seriesSummaries.isEmpty()) {
            item {
                Text(
                    "No series data yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(stats.seriesSummaries, key = { "${it.level}|${it.groupKey}" }) { series ->
                SeriesSummaryCard(series = series, onItemClick = onItemClick)
            }
        }

        // ── Market value note ───────────────────────────────────────────────
        item {
            HelpCard(text = HelpContent.REPORTS_MARKET_NOTE)
        }
    }
}

@Composable
private fun SummaryGrid(stats: CollectionStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Inventory2,
                label = "Owned",
                value = stats.totalOwned.toString(),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.FavoriteBorder,
                label = "Want List",
                value = stats.totalWanted.toString(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Diversity3,
                label = "Franchises",
                value = stats.uniqueFranchises.toString(),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.AttachMoney,
                label = "Market Value",
                value = currencyFmt.format(stats.totalMarketValue),
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CostBreakdownCard(stats: CollectionStats) {
    val saved = stats.totalRetailValue - stats.totalPaid
    Card {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cost Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            BreakdownRow("Total Paid", currencyFmt.format(stats.totalPaid))
            BreakdownRow("Total Retail Value", currencyFmt.format(stats.totalRetailValue))
            BreakdownRow(
                label = if (saved >= 0) "Saved vs Retail" else "Above Retail",
                value = currencyFmt.format(kotlin.math.abs(saved)),
                valueColor = if (saved >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            HorizontalDivider()
            BreakdownRow(
                label = "Est. Market Value",
                value = currencyFmt.format(stats.totalMarketValue),
                valueColor = MaterialTheme.colorScheme.tertiary,
            )

            stats.mostExpensivePaid?.let {
                BreakdownRow("Most Expensive (Paid)", "${it.name} — ${currencyFmt.format(it.pricePaid)}")
            }
            stats.highestMarketValue?.let {
                BreakdownRow("Highest Market Value", "${it.name} — ${currencyFmt.format(it.marketAvg)}")
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
private fun SeriesSummaryCard(
    series: SeriesSummary,
    onItemClick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (series.level == GroupLevel.SET) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    "Set",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            series.groupKey.ifBlank { series.franchise },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (series.category.isNotBlank()) {
                        Text(series.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    if (series.isTracked)
                        "${series.ownedCount} / ${series.displayDenominator}"
                    else
                        "${series.ownedCount}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (series.intent == GroupIntent.COMPLETE)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Bar fill: primary (green-ish) when completing; muted gray when
            // cherry-picking, so an opted-out group doesn't read as "incomplete".
            // Untracked groups (no catalog denominator) show no bar at all.
            if (series.isTracked) {
                LinearProgressIndicator(
                    progress = { (series.completionPct / 100f).coerceIn(0f, 1f) },
                    color = if (series.intent == GroupIntent.COMPLETE)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (series.isTracked) "${series.completionPct}% complete"
                    else "${series.ownedCount} in collection",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Intent pill — or a muted "Not tracked" tag when the group has
                // no catalog denominator (custom/holiday franchises).
                Surface(
                    color = when {
                        !series.isTracked -> MaterialTheme.colorScheme.surfaceVariant
                        series.intent == GroupIntent.COMPLETE -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        when {
                            !series.isTracked -> "Not tracked"
                            series.intent == GroupIntent.COMPLETE -> "Completing"
                            else -> "Cherry-pick"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (series.isTracked && series.intent == GroupIntent.COMPLETE)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (series.missingItems.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide want list (${series.missingItems.size})" else "Show want list (${series.missingItems.size})")
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }

                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        series.missingItems.forEach { item ->
                            // Synthetic "missing original" placeholders share the owned
                            // item's id but represent a variant state, not a navigable
                            // catalog/want-list entry — skip the click target for those.
                            val rowModifier = if (item.id.isNotEmpty() && !item.isMissingOriginal) {
                                Modifier.clickable { onItemClick(item.id) }
                            } else {
                                Modifier
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(rowModifier)
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "${item.seriesNumber} ${item.name}".trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                if (item.effectiveRetail > 0) {
                                    Text(
                                        currencyFmt.format(item.effectiveRetail),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
