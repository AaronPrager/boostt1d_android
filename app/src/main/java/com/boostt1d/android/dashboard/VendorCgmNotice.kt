package com.boostt1d.android.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.GlucoseConnectionOption
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * Explains that a vendor CGM source supplies glucose only. Ported from the iOS
 * DexcomTherapyDataNotice.
 *
 * Dexcom Share and LibreLinkUp have the same limitation, so the wording is shared and only
 * the product name changes. Active insulin, active carbs and the event log all come from
 * Nightscout, and someone watching an empty IOB tile deserves to know why rather than
 * assuming the app is broken.
 */
enum class VendorCgmNoticeContext { DASHBOARD, EVENT_LOG, INSIGHTS, DATA_SOURCE }

/** The vendor a notice is about. Null for sources that have no such limitation. */
fun vendorFor(connection: GlucoseConnectionOption): GlucoseConnectionOption? = when (connection) {
    GlucoseConnectionOption.DEXCOM, GlucoseConnectionOption.LIBRE -> connection
    GlucoseConnectionOption.NIGHTSCOUT, GlucoseConnectionOption.MANUAL -> null
}

private fun shortName(vendor: GlucoseConnectionOption): String = when (vendor) {
    GlucoseConnectionOption.LIBRE -> "Libre"
    else -> "Dexcom"
}

fun vendorNoticeTitle(context: VendorCgmNoticeContext, vendor: GlucoseConnectionOption): String {
    val short = shortName(vendor)
    return when (context) {
        VendorCgmNoticeContext.DASHBOARD -> "Limited data in $short mode"
        VendorCgmNoticeContext.EVENT_LOG -> "Event log not available"
        VendorCgmNoticeContext.INSIGHTS -> "Treatment data not available"
        VendorCgmNoticeContext.DATA_SOURCE -> "$short provides glucose only"
    }
}

fun vendorNoticeMessage(context: VendorCgmNoticeContext, vendor: GlucoseConnectionOption): String {
    val product = vendor.displayName
    val short = shortName(vendor)
    return when (context) {
        VendorCgmNoticeContext.DASHBOARD ->
            "$product only returns glucose readings. Active insulin, active carbs and your " +
                "event log are not available while $short is your glucose source. Switch to " +
                "Nightscout on the Data Source screen if you use Nightscout or a loop system."
        VendorCgmNoticeContext.EVENT_LOG ->
            "$product does not provide meals, boluses or other treatment events. Enter events " +
                "manually on this device, or configure Nightscout to sync them."
        VendorCgmNoticeContext.INSIGHTS ->
            "Pattern insights use treatment history from Nightscout. $product does not provide " +
                "bolus, carb or temp basal data. Switch to Nightscout if you need " +
                "treatment-aware insights."
        VendorCgmNoticeContext.DATA_SOURCE ->
            "$product supplies glucose readings only. Active insulin, active carbs and " +
                "treatment events come from Nightscout. Choose Nightscout as your glucose " +
                "source if you rely on those features."
    }
}

@Composable
fun VendorCgmNotice(
    context: VendorCgmNoticeContext,
    vendor: GlucoseConnectionOption,
    modifier: Modifier = Modifier,
    /** Opens Data Source. Omitted on the Data Source screen itself. */
    onConfigureNightscout: (() -> Unit)? = null,
) {
    val colors = BoostTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.high.copy(alpha = 0.08f), RoundedCornerShape(BoostRadius.md))
            .padding(BoostSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = colors.high, modifier = Modifier.size(18.dp))
            Text(
                vendorNoticeTitle(context, vendor),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary,
            )
        }
        Text(vendorNoticeMessage(context, vendor), fontSize = 12.sp, color = colors.textSecondary)

        if (onConfigureNightscout != null && context != VendorCgmNoticeContext.DATA_SOURCE) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onConfigureNightscout)
                    .padding(top = BoostSpacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                Text(
                    "Configure Nightscout",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primary,
                )
            }
        }
    }
}
