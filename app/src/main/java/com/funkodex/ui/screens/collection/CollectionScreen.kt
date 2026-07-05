package com.funkodex.ui.screens.collection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.funkodex.ui.help.HelpContent
import com.funkodex.util.toHttpsImageUrl
import com.funkodex.ui.help.HelpEmptyState
import coil.compose.AsyncImage
import com.funkodex.data.model.FunkoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onItemClick: (String) -> Unit,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    val uiState     by viewModel.uiState.collectAsState()
    val items       by viewModel.displayedItems.collectAsState()
    val allSeries   by viewModel.allSeries.collectAsState()
    var showFilters by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {

        // Search field — plain OutlinedTextField avoids SearchBar/DockedSearchBar
        // focus system issues that cause Compose to deadlock on initialization.
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

        // Prevent keyboard appearing automatically when screen loads
        LaunchedEffect(Unit) { focusManager.clearFocus() }

        OutlinedTextField(
            value         = uiState.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            placeholder   = { Text("Search collection…") },
            leadingIcon   = { Icon(Icons.Default.Search, null) },
            trailingIcon  = {
                Row {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.setSearchQuery("")
                            focusManager.clearFocus()
                        }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Default.FilterList, "Filter",
                            tint = if (uiState.filterFranchise != null) MaterialTheme.colorScheme.primary
                                   else LocalContentColor.current)
                    }
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            singleLine = true,
            modifier   = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Filter chips
        if (showFilters) {
            FilterRow(
                allSeries      = allSeries,
                selectedSeries = uiState.filterFranchise,
                currentSort    = uiState.sortBy,
                onSelectSeries = viewModel::setFilterSeries,
                onSelectSort   = viewModel::setSortBy,
            )
        }

        // Stats bar
        if (items.isNotEmpty()) {
            // Include variant prices so this total matches the Reports total
            // (Reports sums pricePaid + each variant's pricePaid). Variants also
            // count as owned units, so the item count includes them too.
            val totalPaid  = items.sumOf { it.pricePaid + it.variants.sumOf { v -> v.pricePaid } }
            val totalUnits = items.size + items.sumOf { it.variants.size }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$totalUnits items", style = MaterialTheme.typography.labelMedium)
                    if (totalPaid > 0) Text("Paid: $${"%.2f".format(totalPaid)}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Grid
        when {
            uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> EmptyCollection()
            else -> LazyVerticalGrid(
                columns   = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement   = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    FunkoGridCard(
                        item    = item,
                        onClick = { onItemClick(item.id) },
                        onDelete = { viewModel.deleteItem(item) },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
    allSeries: List<String>,
    selectedSeries: String?,
    currentSort: SortOption,
    onSelectSeries: (String?) -> Unit,
    onSelectSort: (SortOption) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Sort chips
        Text("Sort", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SortOption.values().forEachIndexed { i, opt ->
                SegmentedButton(
                    selected = currentSort == opt,
                    onClick  = { onSelectSort(opt) },
                    shape    = SegmentedButtonDefaults.itemShape(i, SortOption.values().size),
                    label    = { Text(opt.label, fontSize = 11.sp) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Series filter
        if (allSeries.isNotEmpty()) {
            Text("Series", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedSeries == null,
                    onClick  = { onSelectSeries(null) },
                    label    = { Text("All") }
                )
                allSeries.forEach { series ->
                    FilterChip(
                        selected = selectedSeries == series,
                        onClick  = { onSelectSeries(if (selectedSeries == series) null else series) },
                        label    = { Text(series, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FunkoGridCard(
    item: FunkoItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            Box {
                AsyncImage(
                    model             = when {
                        item.imageUrl.isNotEmpty() -> item.imageUrl.toHttpsImageUrl()
                        item.userPhoto != null     -> item.userPhoto
                        item.thumbnailBlob != null -> item.thumbnailBlob
                        else                       -> null
                    },
                    contentDescription = item.name,
                    modifier          = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale      = ContentScale.Fit,
                    placeholder       = null,
                    error             = if (item.userPhoto != null)
                        coil.compose.rememberAsyncImagePainter(item.userPhoto)
                    else if (item.thumbnailBlob != null)
                        coil.compose.rememberAsyncImagePainter(item.thumbnailBlob)
                    else null,
                )
                // Variant count badge — amber warning if any variant is missing a photo
                if (item.variants.isNotEmpty()) {
                    val anyMissingPhoto = item.variants.any { it.photo == null }
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape    = RoundedCornerShape(4.dp),
                        color    = if (anyMissingPhoto)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier            = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment   = Alignment.CenterVertically,
                        ) {
                            if (anyMissingPhoto) {
                                Icon(Icons.Default.PhotoCamera, null,
                                    modifier = Modifier.size(10.dp),
                                    tint     = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Text(
                                "${item.variants.size} VARIANT${if (item.variants.size > 1) "S" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (anyMissingPhoto)
                                    MaterialTheme.colorScheme.onErrorContainer
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                // Missing original badge — bottom-left to avoid overlapping EXCL top-right
                if (item.isMissingOriginal) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Row(
                            modifier              = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Bookmark, null,
                                modifier = Modifier.size(10.dp),
                                tint     = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("NO ORIGINAL",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
                if (item.isExclusive) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        color    = MaterialTheme.colorScheme.tertiary,
                        shape    = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "EXCL",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
                Box(modifier = Modifier.align(Alignment.TopStart)) {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text        = { Text("Delete") },
                            onClick     = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null) }
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(item.name, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                if (item.franchise.isNotEmpty()) {
                    Text(item.franchise, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.pricePaid > 0) {
                    Text("$${"%.2f".format(item.pricePaid)}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyCollection() {
    HelpEmptyState(
        icon        = Icons.Default.Inventory2,
        title       = HelpContent.COLLECTION_EMPTY_TITLE,
        body        = HelpContent.COLLECTION_EMPTY_BODY,
    )
}
