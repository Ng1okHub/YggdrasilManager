package ng1ok.yggdrasilmanager.ui.features

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import ng1ok.yggdrasilmanager.ui.navigation.AppDestination

/**
 * One entry in the toolbox.
 *
 * [summary] is the same one-line description the self-test uses for the matching
 * section, so a tool reads the same here and on the self-test screen.
 */
private data class FeatureItem(
    val destination: AppDestination,
    val icon: ImageVector,
    val summary: String
)

/**
 * Tools that stream core events are the reason to open this screen, so they get
 * full cards; the two environment rewrites are set-and-forget and sit below as
 * lighter rows.
 */
private data class ToolGroup(
    val title: String,
    val prominent: Boolean,
    val tools: List<FeatureItem>
)

private val toolGroups = listOf(
    ToolGroup(
        title = "觀測串流",
        prominent = true,
        tools = listOf(
            FeatureItem(
                destination = AppDestination.Syscall,
                icon = Icons.Default.Terminal,
                summary = "攔截、改寫與引數擷取"
            ),
            FeatureItem(
                destination = AppDestination.Hwbp,
                icon = Icons.Default.Visibility,
                summary = "附加、取代與事件觀察"
            )
        )
    ),
    ToolGroup(
        title = "環境改寫",
        prominent = false,
        tools = listOf(
            FeatureItem(
                destination = AppDestination.HideDbg,
                icon = Icons.Default.BugReport,
                summary = "停止與追蹤狀態改寫"
            ),
            FeatureItem(
                destination = AppDestination.Maps,
                icon = Icons.Default.Map,
                summary = "隱藏或改寫記憶體映射"
            )
        )
    )
)

private val GroupGap = 22.dp
private val ProminentCardGap = 10.dp
private val CompactRowGap = 8.dp

/**
 * One border weight for both levels. Main and lower cards are told apart by
 * their fill and icon block, not by dimming the lower card twice over.
 */
private val CardBorderAlpha = 0.65f

/** Leading slot shared by both levels so the tool names start on one line. */
private val ToolIconSlot = 46.dp

/** Gap between that slot and the labels; one value for both levels. */
private val ToolIconGap = 14.dp

/** One entry of the first-entry stagger; the whole run stays well under 600 ms. */
private const val EntryStepMillis = 60

@Composable
fun FeaturesScreen(navController: NavController) {
    // The stagger runs once for the whole panel. Holding the flag here rather
    // than inside each item keeps it from replaying if a row is ever recycled.
    val hasEntered = rememberHasEntered()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 12.dp,
                end = 20.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(GroupGap)
        ) {
            itemsIndexed(toolGroups, key = { _, group -> group.title }) { index, group ->
                ToolGroupSection(
                    group = group,
                    entryOffset = toolGroups.take(index).sumOf { it.tools.size },
                    hasEntered = hasEntered,
                    onOpen = { navController.navigate(it.route) }
                )
            }
        }
    }
}

@Composable
private fun ToolGroupSection(
    group: ToolGroup,
    entryOffset: Int,
    hasEntered: Boolean,
    onOpen: (AppDestination) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = group.title,
            modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(
                if (group.prominent) ProminentCardGap else CompactRowGap
            )
        ) {
            group.tools.forEachIndexed { index, tool ->
                if (group.prominent) {
                    ProminentToolCard(
                        tool = tool,
                        entryIndex = entryOffset + index,
                        hasEntered = hasEntered,
                        onClick = { onOpen(tool.destination) }
                    )
                } else {
                    CompactToolRow(
                        tool = tool,
                        entryIndex = entryOffset + index,
                        hasEntered = hasEntered,
                        onClick = { onOpen(tool.destination) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProminentToolCard(
    tool: FeatureItem,
    entryIndex: Int,
    hasEntered: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by rememberPressScale(pressed)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .entryMotion(entryIndex = entryIndex, hasEntered = hasEntered, pressScale = pressScale)
            .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CardBorderAlpha)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(ToolIconGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(ToolIconSlot),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tool.icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            ToolLabels(
                tool = tool,
                nameStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Chevron(size = 20.dp)
        }
    }
}

@Composable
private fun CompactToolRow(
    tool: FeatureItem,
    entryIndex: Int,
    hasEntered: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by rememberPressScale(pressed)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .entryMotion(entryIndex = entryIndex, hasEntered = hasEntered, pressScale = pressScale)
            .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = CardBorderAlpha)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(ToolIconGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(ToolIconSlot),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tool.icon,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
            ToolLabels(
                tool = tool,
                nameStyle = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Chevron(size = 18.dp)
        }
    }
}

@Composable
private fun ToolLabels(
    tool: FeatureItem,
    nameStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = tool.destination.title,
            style = nameStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = tool.summary,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Chevron(size: Dp) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(size)
    )
}

/**
 * Press feedback: the row settles under the finger and snaps back. Short and
 * immediate; the ripple carries the rest.
 */
@Composable
private fun rememberPressScale(pressed: Boolean) = animateFloatAsState(
    targetValue = if (pressed) 0.98f else 1f,
    animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
    label = "press-scale"
)

/**
 * First entry: the tools light up in reading order, once, and only when the
 * platform is animating at all. The per-item delay lives in the animation spec
 * so a row recycled mid-run still lands in place instead of replaying.
 */
@Composable
private fun Modifier.entryMotion(
    entryIndex: Int,
    hasEntered: Boolean,
    pressScale: Float
): Modifier {
    val motionEnabled = rememberMotionEnabled()
    // When motion is off, both the delay and the distance are dropped so the
    // tools are simply there.
    val startDelay = if (motionEnabled) entryIndex * EntryStepMillis else 0
    val alpha by animateFloatAsState(
        targetValue = if (hasEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 220, delayMillis = startDelay),
        label = "entry-alpha"
    )
    val rise by animateFloatAsState(
        targetValue = if (hasEntered) 0f else if (motionEnabled) 12f else 0f,
        animationSpec = tween(durationMillis = 240, delayMillis = startDelay),
        label = "entry-rise"
    )
    return this.graphicsLayer {
        scaleX = pressScale
        scaleY = pressScale
        this.alpha = alpha
        translationY = rise.dp.toPx()
    }
}

/**
 * Drives the first-entry stagger for the whole panel.
 *
 * Flips after a single frame so the pre-entry state is what renders first, then
 * every tool starts its own delayed animation. The staggering lives in the
 * per-item delay (see [entryMotion]) rather than here, so the first tool starts
 * moving immediately instead of waiting for the last one's turn.
 */
@Composable
private fun rememberHasEntered(): Boolean {
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!entered) {
            withFrameNanos { }
            entered = true
        }
    }
    return entered
}

/**
 * Whether the platform is animating at all. When the duration scale is zero the
 * first-entry stagger is skipped so nothing ever sits in its transparent state.
 */
@Composable
private fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { animatorDurationScale(context) > 0f }
}

private fun animatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )
