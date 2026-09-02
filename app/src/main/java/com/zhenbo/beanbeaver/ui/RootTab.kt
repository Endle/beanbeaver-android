package com.zhenbo.beanbeaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhenbo.beanbeaver.ui.theme.BbAccent
import com.zhenbo.beanbeaver.ui.theme.bbCardFill
import com.zhenbo.beanbeaver.ui.theme.bbInkSecondary

/**
 * The three places the tab bar goes.
 *
 * **Scan is an action, not a place.** It has a tab item because that is where a
 * thumb goes for the app's main verb, but selecting it opens the camera and
 * leaves you on the tab you were already on. So there is no scan screen to
 * restore, and the scan *result* replaces the whole shell rather than being a
 * fourth destination.
 *
 * Spending, Receipts and Import are pushes off Home. They were cards and buttons
 * on the home screen and are rows on it now; giving each a tab would put four
 * equally-weighted destinations in a bar where only one of them is where you
 * start.
 */
enum class RootTab { HOME, SCAN, SETTINGS }

/** How far the Scan circle stands proud of the bar's top edge. */
private val SCAN_LIFT = 16.dp

/**
 * The app's bottom navigation, with the raised Scan circle over its middle slot.
 *
 * # Why the circle is drawn rather than configured
 *
 * A raised centre action is not something [NavigationBar] offers, so it cannot
 * come from the component however much of the rest of the bar does. What *is*
 * from the component is everything underneath: the real `NavigationBar` still
 * lays out three slots, draws the bar, handles the window insets, and owns
 * selection and accessibility. This lays one circle over the middle slot, and the
 * item beneath it stays tappable and does the same thing.
 *
 * So the failure mode is mild by construction. If the bar's own metrics move
 * under a future Material release, the circle is mispositioned over an item that
 * still works, rather than the navigation being broken.
 *
 * **Flat card colour, never Material tonal elevation** — the app-wide rule.
 * Elevation tints with `primary`, which here is the brand red, so an elevated
 * bar comes out pink.
 */
@Composable
fun BbNavigationBar(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        NavigationBar(
            // The lift, spent as layout rather than as an overflow: the box is
            // this much taller than the bar, so the circle above stays inside
            // its own bounds. A negative offset would draw outside them and be
            // clipped by whichever slot the bar is placed in.
            modifier = Modifier.align(Alignment.BottomCenter).padding(top = SCAN_LIFT),
            containerColor = bbCardFill,
            // The bar reads as a surface laid over the page, the same way every
            // card on the screen does — so it takes the card fill, not the
            // canvas, and Material's own tonal overlay is switched off.
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(
                selected = selected == RootTab.HOME,
                onClick = { onSelect(RootTab.HOME) },
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text("Home") },
                colors = tabColors(),
            )
            // The middle slot exists so the bar lays out three even columns and
            // so a tap that misses the circle still scans. Its own icon is
            // blank — the circle above is the glyph.
            NavigationBarItem(
                selected = false,
                onClick = onScan,
                icon = { Box(Modifier.size(24.dp)) },
                label = { Text("Scan") },
                colors = tabColors(),
            )
            NavigationBarItem(
                selected = selected == RootTab.SETTINGS,
                onClick = { onSelect(RootTab.SETTINGS) },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Settings") },
                colors = tabColors(),
            )
        }

        // Sits so roughly a third of the circle clears the bar's top edge. The
        // item's own label stays visible below it, which is what keeps this
        // recognisable as a tab rather than a floating action button.
        //
        // It carries its own `clickable` rather than letting taps fall through:
        // the top of the circle is above the bar, where there is no item to fall
        // through to.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(52.dp)
                .shadow(8.dp, CircleShape, spotColor = BbAccent)
                .clip(CircleShape)
                .background(BbAccent)
                .clickable(onClick = onScan)
                .semantics { contentDescription = "Scan a receipt" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun tabColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = BbAccent,
    selectedTextColor = BbAccent,
    unselectedIconColor = bbInkSecondary,
    unselectedTextColor = bbInkSecondary,
    // The pill behind a selected item is Material's own affordance and tints with
    // `primary`; on the brand red it reads as a second, softer button sitting in
    // the bar. Selection is carried by the accent glyph instead.
    indicatorColor = Color.Transparent,
)
