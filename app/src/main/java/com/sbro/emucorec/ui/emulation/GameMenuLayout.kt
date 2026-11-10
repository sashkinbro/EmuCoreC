package com.sbro.emucorec.ui.emulation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.R
import com.sbro.emucorec.data.GameMenuLayoutStyle
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonShapeCorners

// Visual layout and navigation ported from EmuCoreX GameMenuLayoutHost.
@Composable
internal fun GameMenuLayoutHost(
    style: GameMenuLayoutStyle,
    modifier: Modifier,
    menuTabs: List<EmulationMenuTab>,
    selectedMenuTab: EmulationMenuTab,
    selectedTabScrollState: ScrollState,
    railFocusRequesters: Map<EmulationMenuTab, FocusRequester>,
    contentBottomPadding: Dp,
    rightInset: Dp,
    sectionSpacing: Dp,
    onSelectTab: (EmulationMenuTab) -> Unit,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val safeModifier = modifier
        .testTag("game-menu")
        .padding(WindowInsets.displayCutout.asPaddingValues())
        .padding(WindowInsets.statusBars.asPaddingValues())

    when (style) {
        GameMenuLayoutStyle.SIDEBAR -> {
            Row(
                modifier = safeModifier.padding(end = 16.dp + rightInset, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top
            ) {
                GameMenuContentSurface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 300.dp, max = 420.dp),
                    shape = neonShape(28.dp),
                    scrollState = selectedTabScrollState,
                    bottomPadding = contentBottomPadding,
                    sectionSpacing = sectionSpacing,
                    content = content
                )
                Spacer(modifier = Modifier.width(12.dp))
                GameMenuVerticalNavigation(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(74.dp),
                    menuTabs = menuTabs,
                    selectedMenuTab = selectedMenuTab,
                    railFocusRequesters = railFocusRequesters,
                    showLabels = false,
                    onSelectTab = onSelectTab,
                    onClose = onClose
                )
            }
        }

        GameMenuLayoutStyle.DASHBOARD -> {
            Box(
                modifier = safeModifier
                    .fillMaxSize()
                    .padding(end = rightInset, top = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(),
                    shape = neonShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    shadowElevation = 12.dp
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        GameMenuVerticalNavigation(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(196.dp),
                            menuTabs = menuTabs,
                            selectedMenuTab = selectedMenuTab,
                            railFocusRequesters = railFocusRequesters,
                            showLabels = true,
                            onSelectTab = onSelectTab,
                            onClose = onClose
                        )
                        GameMenuContentColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            scrollState = selectedTabScrollState,
                            bottomPadding = 18.dp,
                            sectionSpacing = sectionSpacing,
                            content = content
                        )
                    }
                }
            }
        }

        GameMenuLayoutStyle.COMMAND_CENTER -> {
            Box(
                modifier = safeModifier
                    .fillMaxSize()
                    .padding(end = rightInset, top = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .fillMaxHeight(),
                    shape = neonShapeCorners(topStart = 30.dp, topEnd = 30.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
                    shadowElevation = 14.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        GameMenuHorizontalNavigation(
                            menuTabs = menuTabs,
                            selectedMenuTab = selectedMenuTab,
                            railFocusRequesters = railFocusRequesters,
                            iconOnly = false,
                            onSelectTab = onSelectTab,
                            onClose = onClose
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        GameMenuContentColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            scrollState = selectedTabScrollState,
                            bottomPadding = 18.dp,
                            sectionSpacing = sectionSpacing,
                            content = content
                        )
                    }
                }
            }
        }

        GameMenuLayoutStyle.COMPACT -> {
            Box(
                modifier = safeModifier
                    .fillMaxSize()
                    .padding(end = 12.dp + rightInset, top = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 300.dp, max = 352.dp),
                    shape = neonShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    shadowElevation = 10.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        GameMenuHorizontalNavigation(
                            menuTabs = menuTabs,
                            selectedMenuTab = selectedMenuTab,
                            railFocusRequesters = railFocusRequesters,
                            iconOnly = true,
                            pinClose = true,
                            onSelectTab = onSelectTab,
                            onClose = onClose
                        )
                        GameMenuContentColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            scrollState = selectedTabScrollState,
                            bottomPadding = contentBottomPadding,
                            sectionSpacing = 10.dp,
                            horizontalPadding = 12.dp,
                            verticalPadding = 12.dp,
                            content = content
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameMenuContentSurface(
    modifier: Modifier,
    shape: Shape,
    scrollState: ScrollState,
    bottomPadding: Dp,
    sectionSpacing: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        // A translucent surface no longer matches a scheme colour, so Surface cannot derive
        // the content colour and would inherit the framework default black: text without an
        // explicit colour (captions, hints) rendered dark-on-dark inside the game menu.
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    ) {
        GameMenuContentColumn(
            scrollState = scrollState,
            bottomPadding = bottomPadding,
            sectionSpacing = sectionSpacing,
            content = content
        )
    }
}

@Composable
private fun GameMenuContentColumn(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    bottomPadding: Dp,
    sectionSpacing: Dp,
    horizontalPadding: Dp = 18.dp,
    verticalPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .testTag("game-menu-content")
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        content = content
    )
}

@Composable
private fun GameMenuVerticalNavigation(
    modifier: Modifier,
    menuTabs: List<EmulationMenuTab>,
    selectedMenuTab: EmulationMenuTab,
    railFocusRequesters: Map<EmulationMenuTab, FocusRequester>,
    showLabels: Boolean,
    onSelectTab: (EmulationMenuTab) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier,
            shape = if (showLabels) neonShapeCorners(topStart = 24.dp, bottomStart = 24.dp) else neonShape(24.dp),
        color = if (showLabels) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f))
    ) {
        val railScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(railScrollState)
                .padding(vertical = 16.dp, horizontal = if (showLabels) 12.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            menuTabs.forEach { tab ->
                if (showLabels) {
                    GameMenuNavigationTile(
                        modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab)),
                        tab = tab,
                        selected = selectedMenuTab == tab,
                        iconOnly = false,
                        onClick = { onSelectTab(tab) }
                    )
                } else {
                    EmulationMenuRailButton(
                        modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab))
                            .testTag("game-menu-tab-${tab.name}").semantics { this.selected = selectedMenuTab == tab },
                        icon = gameMenuTabIcon(tab),
                        contentDescription = gameMenuTabLabel(tab),
                        selected = selectedMenuTab == tab,
                        onClick = { onSelectTab(tab) }
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (showLabels) {
                GameMenuNavigationTile(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    label = stringResource(R.string.emulation_close_menu),
                    selected = false,
                    destructive = true,
                    iconOnly = false,
                    onClick = onClose
                )
            } else {
                EmulationMenuRailButton(
                    modifier = Modifier.testTag("game-menu-close"),
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    contentDescription = stringResource(R.string.emulation_close_menu),
                    selected = false,
                    onClick = onClose,
                    isDestructive = true
                )
            }
        }
    }
}

@Composable
private fun GameMenuHorizontalNavigation(
    menuTabs: List<EmulationMenuTab>,
    selectedMenuTab: EmulationMenuTab,
    railFocusRequesters: Map<EmulationMenuTab, FocusRequester>,
    iconOnly: Boolean,
    pinClose: Boolean = false,
    onSelectTab: (EmulationMenuTab) -> Unit,
    onClose: () -> Unit
) {
    val selectedIndex = menuTabs.indexOf(selectedMenuTab).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(selectedMenuTab) {
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (visible == null || visible.offset < 0 ||
            visible.offset + visible.size > listState.layoutInfo.viewportEndOffset) {
            listState.animateScrollToItem(selectedIndex)
        }
    }
    if (pinClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(menuTabs, key = { it.name }) { tab ->
                    GameMenuNavigationTile(
                        modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab)),
                        tab = tab,
                        selected = selectedMenuTab == tab,
                        iconOnly = iconOnly,
                        fillWidth = false,
                        onClick = { onSelectTab(tab) }
                    )
                }
            }
            GameMenuNavigationTile(
                modifier = Modifier.padding(end = 12.dp),
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                label = stringResource(R.string.emulation_close_menu),
                selected = false,
                destructive = true,
                iconOnly = iconOnly,
                fillWidth = false,
                onClick = onClose
            )
        }
        return
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(menuTabs, key = { it.name }) { tab ->
            GameMenuNavigationTile(
                modifier = Modifier.focusRequester(railFocusRequesters.getValue(tab)),
                tab = tab,
                selected = selectedMenuTab == tab,
                iconOnly = iconOnly,
                fillWidth = false,
                onClick = { onSelectTab(tab) }
            )
        }
        item(key = "close") {
            GameMenuNavigationTile(
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                label = stringResource(R.string.emulation_close_menu),
                selected = false,
                destructive = true,
                iconOnly = iconOnly,
                fillWidth = false,
                onClick = onClose
            )
        }
    }
}

@Composable
private fun GameMenuNavigationTile(
    modifier: Modifier = Modifier,
    tab: EmulationMenuTab? = null,
    icon: ImageVector? = null,
    label: String? = null,
    selected: Boolean,
    destructive: Boolean = false,
    iconOnly: Boolean,
    fillWidth: Boolean = !iconOnly,
    onClick: () -> Unit
) {
    val resolvedIcon = icon ?: tab?.let(::gameMenuTabIcon) ?: Icons.Rounded.Menu
    val resolvedLabel = label ?: tab?.let { gameMenuTabLabel(it) }.orEmpty()
            val shape = neonShape(if (iconOnly) 14.dp else 16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        modifier = modifier
            .then(if (tab != null) Modifier.testTag("game-menu-tab-${tab.name}").semantics { this.selected = selected }
                else Modifier.testTag("game-menu-close"))
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        shape = shape,
        color = when {
            destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f)
        },
        border = BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            when {
                isFocused -> MaterialTheme.colorScheme.primary
                destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (iconOnly) 13.dp else 14.dp,
                vertical = if (iconOnly) 12.dp else 13.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = resolvedIcon,
                contentDescription = resolvedLabel,
                tint = when {
                    destructive -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(21.dp)
            )
            if (!iconOnly) {
                Text(
                    text = resolvedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        destructive -> MaterialTheme.colorScheme.error
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmulationMenuRailButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val shape = neonShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val iconScale by animateFloatAsState(
        targetValue = if (selected && !isDestructive) 1.08f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "emulation_menu_rail_icon_scale"
    )
    val border = BorderStroke(
        if (isFocused) 2.dp else 1.dp,
        when {
            isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)
            isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
        }
    )
    Surface(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        shape = shape,
        color = when {
            isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        },
        border = border
    ) {
        Box(
            modifier = Modifier
                .size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    isDestructive -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer(
                        scaleX = iconScale,
                        scaleY = iconScale
                    )
            )
        }
    }
}

private fun gameMenuTabIcon(tab: EmulationMenuTab): ImageVector = when (tab) {
    EmulationMenuTab.Game -> Icons.Rounded.Menu
    EmulationMenuTab.SaveStates -> Icons.Rounded.Save
    EmulationMenuTab.Graphics -> Icons.Rounded.Fullscreen
    EmulationMenuTab.Audio -> Icons.AutoMirrored.Rounded.VolumeUp
    EmulationMenuTab.Portals -> Icons.Rounded.Usb
    EmulationMenuTab.Controls -> Icons.Rounded.Gamepad
    EmulationMenuTab.Achievements -> Icons.Rounded.LockOpen
    EmulationMenuTab.Gamepad -> Icons.Rounded.SportsEsports
}

@Composable
private fun gameMenuTabLabel(tab: EmulationMenuTab): String = stringResource(when (tab) {
    EmulationMenuTab.Game -> R.string.emulation_tab_game
    EmulationMenuTab.SaveStates -> R.string.emulation_tab_savestates
    EmulationMenuTab.Graphics -> R.string.settings_tab_graphics
    EmulationMenuTab.Audio -> R.string.settings_tab_audio
    EmulationMenuTab.Portals -> R.string.emulation_tab_portals
    EmulationMenuTab.Controls -> R.string.emulation_tab_controls
    EmulationMenuTab.Achievements -> R.string.emulation_tab_achievements
    EmulationMenuTab.Gamepad -> R.string.emulation_tab_gamepad
})

@Composable
internal fun GameMenuButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    gradientColors: List<Color>? = null,
    enabled: Boolean = true,
    showProgress: Boolean = false,
    containerColor: Color? = null
) {
    val shape = neonShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(enabled = enabled, interactionSource = interactionSource),
        shape = shape,
        color = when {
            gradientColors != null -> Color.Transparent
            containerColor != null -> containerColor
            isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        border = if (gradientColors == null) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) else null
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (gradientColors != null) {
                        Modifier.background(
                            Brush.horizontalGradient(gradientColors.map { it.copy(alpha = 0.22f) }),
                            neonShape(16.dp)
                        ).border(1.dp, gradientColors.first().copy(alpha = 0.3f), neonShape(16.dp))
                    } else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    gradientColors != null -> gradientColors.first()
                    isDestructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            if (showProgress) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

