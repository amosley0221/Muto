package dev.muto.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muto.app.R
import dev.muto.app.ui.MutoViewModel
import dev.muto.core.dns.BlockMode
import dev.muto.core.filter.UpstreamResolvers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MutoViewModel, onOpenApps: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showBlockMode by remember { mutableStateOf(false) }
    var showResolver by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsGroup(stringResource(R.string.settings_group_filtering)) {
                    ClickableRow(
                        title = stringResource(R.string.settings_block_mode),
                        subtitle = stringResource(settings.blockMode.labelRes()),
                        onClick = { showBlockMode = true },
                    )
                    HorizontalDivider()
                    ClickableRow(
                        title = stringResource(R.string.settings_apps),
                        subtitle = if (settings.bypassedApps.isEmpty()) {
                            stringResource(R.string.settings_apps_none)
                        } else {
                            stringResource(R.string.settings_apps_count, settings.bypassedApps.size)
                        },
                        onClick = onOpenApps,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_network)) {
                    ClickableRow(
                        title = stringResource(R.string.settings_resolver),
                        subtitle = UpstreamResolvers.byId(settings.upstreamResolverId)?.title
                            ?: settings.upstreamResolverId,
                        onClick = { showResolver = true },
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_ipv6),
                        subtitle = stringResource(R.string.settings_ipv6_help),
                        checked = settings.ipv6Enabled,
                        onCheckedChange = viewModel::setIpv6Enabled,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_updates)) {
                    ClickableRow(
                        title = stringResource(R.string.settings_update_interval),
                        subtitle = stringResource(
                            R.string.settings_update_interval_value,
                            settings.updateIntervalHours,
                        ),
                        onClick = {
                            // Cycle through the sensible intervals rather than open a dialog for
                            // a setting almost nobody changes twice.
                            val options = listOf(6, 12, 24, 72, 168)
                            val next = options.firstOrNull { it > settings.updateIntervalHours } ?: options.first()
                            viewModel.setUpdateIntervalHours(next)
                        },
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_update_unmetered),
                        subtitle = stringResource(R.string.settings_update_unmetered_help),
                        checked = settings.updateOnUnmeteredOnly,
                        onCheckedChange = viewModel::setUpdateOnUnmeteredOnly,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_history)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_keep_history),
                        subtitle = stringResource(R.string.settings_keep_history_help),
                        checked = settings.keepHistory,
                        onCheckedChange = viewModel::setKeepHistory,
                    )
                    if (settings.keepHistory) {
                        HorizontalDivider()
                        ClickableRow(
                            title = stringResource(R.string.settings_retention),
                            subtitle = stringResource(
                                R.string.settings_retention_value,
                                settings.historyRetentionHours,
                            ),
                            onClick = {
                                val options = listOf(6, 24, 72, 168)
                                val next = options.firstOrNull { it > settings.historyRetentionHours }
                                    ?: options.first()
                                viewModel.setHistoryRetentionHours(next)
                            },
                        )
                    }
                    HorizontalDivider()
                    ClickableRow(
                        title = stringResource(R.string.settings_clear_history),
                        subtitle = stringResource(R.string.settings_clear_history_help),
                        onClick = viewModel::clearHistory,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_startup)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_start_on_boot),
                        subtitle = stringResource(R.string.settings_start_on_boot_help),
                        checked = settings.startOnBoot,
                        onCheckedChange = viewModel::setStartOnBoot,
                    )
                }
            }

            item {
                Text(
                    stringResource(R.string.settings_about),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showBlockMode) {
        ChoiceDialog(
            title = stringResource(R.string.settings_block_mode),
            options = BlockMode.entries,
            selected = settings.blockMode,
            labelFor = { stringResource(it.labelRes()) },
            descriptionFor = { stringResource(it.descriptionRes()) },
            onSelect = { viewModel.setBlockMode(it); showBlockMode = false },
            onDismiss = { showBlockMode = false },
        )
    }

    if (showResolver) {
        ChoiceDialog(
            title = stringResource(R.string.settings_resolver),
            options = UpstreamResolvers.ALL,
            selected = UpstreamResolvers.byId(settings.upstreamResolverId),
            labelFor = { it.title },
            descriptionFor = { it.description },
            onSelect = { viewModel.setUpstreamResolver(it.id); showResolver = false },
            onDismiss = { showResolver = false },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) { Column { content() } }
    }
}

@Composable
private fun ClickableRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    labelFor: @Composable (T) -> String,
    descriptionFor: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { value ->
                    val label = labelFor(value)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Column(Modifier.padding(start = 8.dp, top = 12.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            val description = descriptionFor(value)
                            if (description.isNotBlank()) {
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private fun BlockMode.labelRes() = when (this) {
    BlockMode.NXDOMAIN -> R.string.block_mode_nxdomain
    BlockMode.NULL_IP -> R.string.block_mode_null_ip
    BlockMode.REFUSED -> R.string.block_mode_refused
}

private fun BlockMode.descriptionRes() = when (this) {
    BlockMode.NXDOMAIN -> R.string.block_mode_nxdomain_help
    BlockMode.NULL_IP -> R.string.block_mode_null_ip_help
    BlockMode.REFUSED -> R.string.block_mode_refused_help
}
