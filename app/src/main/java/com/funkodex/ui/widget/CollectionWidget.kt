package com.funkodex.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManagerReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import com.funkodex.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CollectionWidget — E3
 *
 * 4×2 Jetpack Glance home screen widget.
 *
 * Shows:
 *   - Collection count (owned items)
 *   - Total market value estimate
 *   - Top wanted item name (first item on want list)
 *
 * Updates:
 *   - Automatically every hour (updatePeriodMillis in widget_info XML)
 *   - Immediately when the app updates the collection (call update() from FunkoRepository)
 *
 * Tap action: opens MainActivity (deep-link to collection screen).
 *
 * Data is stored in Glance Preferences DataStore — a lightweight key-value
 * store that doesn't require the main Couchbase DB to be open.
 * FunkoRepository updates the widget data after any collection change.
 */
class CollectionWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> =
        PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs       = currentState<androidx.datastore.preferences.core.Preferences>()
            val ownedCount  = prefs[OWNED_COUNT_KEY]  ?: 0
            val marketValue = prefs[MARKET_VALUE_KEY] ?: 0.0
            val topWanted   = prefs[TOP_WANTED_KEY]   ?: ""

            WidgetContent(
                ownedCount  = ownedCount,
                marketValue = marketValue,
                topWanted   = topWanted,
                context     = context,
            )
        }
    }

    companion object {
        val OWNED_COUNT_KEY  = androidx.datastore.preferences.core.intPreferencesKey("owned_count")
        val MARKET_VALUE_KEY = androidx.datastore.preferences.core.doublePreferencesKey("market_value")
        val TOP_WANTED_KEY   = androidx.datastore.preferences.core.stringPreferencesKey("top_wanted")

        /**
         * Call from FunkoRepository after any collection change to keep widget in sync.
         * Safe to call from any coroutine — no-ops if widget is not pinned.
         */
        suspend fun update(
            context:     Context,
            ownedCount:  Int,
            marketValue: Double,
            topWanted:   String,
        ) = withContext(Dispatchers.IO) {
            try {
                val manager = GlanceAppWidgetManager(context)
                val ids     = manager.getGlanceIds(CollectionWidget::class.java)
                ids.forEach { id ->
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[OWNED_COUNT_KEY]  = ownedCount
                            this[MARKET_VALUE_KEY] = marketValue
                            this[TOP_WANTED_KEY]   = topWanted
                        }
                    }
                    CollectionWidget().update(context, id)
                }
            } catch (_: Exception) {
                // Widget not pinned — silently ignore
            }
        }
    }
}

@Composable
private fun WidgetContent(
    ownedCount:  Int,
    marketValue: Double,
    topWanted:   String,
    context:     Context,
) {
    val navyBg = GlanceModifier.background(Color(0xFF0D1B2A))

    Box(
        modifier        = GlanceModifier
            .fillMaxSize()
            .then(navyBg)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier            = GlanceModifier.fillMaxSize(),
            verticalAlignment   = Alignment.CenterVertically,
        ) {
            // App title row
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "FunkoDex",
                    style = TextStyle(
                        color      = ColorProvider(Color(0xFFF0F4F8)),
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }

            Spacer(GlanceModifier.height(4.dp))

            // Stats row
            Row(horizontalAlignment = Alignment.Start) {
                Text(
                    text  = "$ownedCount owned",
                    style = TextStyle(
                        color    = ColorProvider(Color(0xFF5DADE2)),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.width(8.dp))
                if (marketValue > 0) {
                    Text(
                        text  = "· \$${"%.0f".format(marketValue)} est. value",
                        style = TextStyle(
                            color    = ColorProvider(Color(0xFFB8943F)),
                            fontSize = 13.sp,
                        ),
                    )
                }
            }

            if (topWanted.isNotEmpty()) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text  = "Want: $topWanted",
                    style = TextStyle(
                        color    = ColorProvider(Color(0xFF8AAABB)),
                        fontSize = 12.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * AppWidgetProvider that triggers widget updates.
 * Registered in AndroidManifest.xml with APPWIDGET_UPDATE intent filter.
 */
class CollectionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CollectionWidget()
}
