package com.boostt1d.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.boostt1d.android.R

/**
 * The app icon's heart, as a tintable mark. Ported from the iOS BoostHeartMark.
 *
 * The artwork is white on transparency, so the tint decides the colour: the same file serves
 * a white mark on a coloured badge and a brand-blue one on a plain screen, and it follows the
 * palette into dark mode rather than staying the icon's fixed blue.
 */
@Composable
fun BoostHeartMark(
    width: Dp,
    modifier: Modifier = Modifier,
    tint: Color = BoostTheme.colors.primary,
) {
    Image(
        painter = painterResource(R.drawable.boost_heart),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(width, width / ASPECT_RATIO),
    )
}

/**
 * The artwork's own proportions, 2066 by 1769, trimmed to its ink, so callers size by width
 * alone and the height follows.
 */
private const val ASPECT_RATIO = 2066f / 1769f
