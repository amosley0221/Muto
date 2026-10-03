package dev.muto.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muto.app.R
import dev.muto.app.data.ProtectionMode
import dev.muto.app.tunnel.TunnelConnection
import dev.muto.app.tunnel.TunnelStatus
import dev.muto.app.ui.MutoViewModel
import dev.muto.app.vpn.ProtectionState
import java.text.NumberFormat

@Composable
fun HomeScreen(
    viewModel: MutoViewModel,
    onRequestProtection: () -> Unit,
    onOpenLog: () -> Unit,
    onManageTunnels: () -> Unit,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val recent by viewModel.liveLog.collectAsStateWithLifecycle()
    val tunnels by viewModel.tunnels.collectAsStateWithLifecycle()

    val tunnelling = settings.protectionMode == ProtectionMode.TUNNEL

    // One card shows "is protection on", but what that means depends on the mode, so the two
    // sources of truth are folded into a single state before anything is drawn.
    val effectiveState = if (tunnelling) tunnelStatus.asProtectionState() else status.state

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModeSelector(
            mode = settings.protectionMode,
            onModeChange = viewModel::setProtectionMode,
        )

        ProtectionCard(
            state = effectiveState,
            message = if (tunnelling) tunnelStatus.error else status.message,
            canPause = !tunnelling,
            onStart = onRequestProtection,
            onStop = viewModel::stopProtection,
            onTogglePause = viewModel::togglePause,
        )

        if (tunnelling) {
            TunnelCard(
                status = tunnelStatus,
                serverName = tunnels.firstOrNull { it.id == settings.activeTunnelId }?.name
                    ?: tunnels.firstOrNull()?.name,
                onManage = onManageTunnels,
            )
            NoticeCard(
                title = stringResource(R.string.mode_tunnel),
                body = stringResource(R.string.tunnel_filtering_note),
            )
        } else {
            StatsRow(
                blocked = stats.blocked,
                allowed = stats.allowed,
                blockedFraction = stats.blockedFraction,
            )
            RuleCountCard(
                loading = filterState.loading,
                listRules = filterState.listRuleCount,
                userRules = filterState.userRuleCount,
            )
            RecentlyBlockedCard(
                entries = recent.filter { it.blocked }.take(6),
                onOpenLog = onOpenLog,
            )
            NoticeCard(
                title = stringResource(R.string.home_limits_title),
                body = stringResource(R.string.home_limits_body),
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun TunnelStatus.asProtectionState(): ProtectionState = when (connection) {
    TunnelConnection.CONNECTED -> ProtectionState.RUNNING
    TunnelConnection.CONNECTING -> ProtectionState.STARTING
    TunnelConnection.FAILED -> ProtectionState.FAILED
    TunnelConnection.DISCONNECTED -> ProtectionState.STOPPED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: ProtectionMode, onModeChange: (ProtectionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ProtectionMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, ProtectionMode.entries.size),
                    icon = {
                        Icon(
                            if (option == ProtectionMode.FILTER) Icons.Filled.Shield else Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            if (option == ProtectionMode.FILTER) R.string.mode_filter else R.string.mode_tunnel,
                        ),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.mode_help),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProtectionCard(
    state: ProtectionState,
    message: String?,
    canPause: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val active = state == ProtectionState.RUNNING
    val container by animateColorAsState(
        when (state) {
            ProtectionState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
            ProtectionState.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
            ProtectionState.FAILED -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "protection-container",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state == ProtectionState.STARTING) {
                CircularProgressIndicator(Modifier.size(56.dp))
            } else {
                Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(56.dp))
            }

            Text(
                text = stringResource(
                    when (state) {
                        ProtectionState.RUNNING -> R.string.status_protected
                        ProtectionState.PAUSED -> R.string.status_paused
                        ProtectionState.STARTING -> R.string.status_starting
                        ProtectionState.FAILED -> R.string.status_failed
                        ProtectionState.STOPPED -> R.string.status_off
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }

            if (state == ProtectionState.STOPPED || state == ProtectionState.FAILED) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_turn_on))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Pause only means something for the filter: there is no half-connected
                    // state for a tunnel, so offering it there would be a lie.
                    if (canPause) {
                        OutlinedButton(onClick = onTogglePause, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (active) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(if (active) R.string.action_pause else R.string.action_resume))
                        }
                    }
                    Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_turn_off))
                    }
                }
            }
        }
    }
}

@Composable
private fun TunnelCard(status: TunnelStatus, serverName: String?, onManage: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        serverName ?: stringResource(R.string.tunnel_no_server),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            when (status.connection) {
                                TunnelConnection.CONNECTED -> R.string.tunnel_state_connected
                                TunnelConnection.CONNECTING -> R.string.tunnel_state_connecting
                                TunnelConnection.FAILED -> R.string.tunnel_state_failed
                                TunnelConnection.DISCONNECTED -> R.string.tunnel_state_disconnected
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.endpoint?.let {
                        Text(
                            stringResource(R.string.tunnel_endpoint, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onManage) { Text(stringResource(R.string.tunnel_manage)) }
            }
        }
    }
}

@Composable
private fun StatsRow(blocked: Long, allowed: Long, blockedFraction: Float) {
    val format = remember { NumberFormat.getIntegerInstance() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(Modifier.weight(1f), format.format(blocked), stringResource(R.string.stat_blocked))
        StatTile(Modifier.weight(1f), format.format(allowed), stringResource(R.string.stat_allowed))
        StatTile(Modifier.weight(1f), "${(blockedFraction * 100).toInt()}%", stringResource(R.string.stat_share))
    }
}

@Composable
private fun StatTile(modifier: Modifier = Modifier, value: String, label: String) {
    Card(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RuleCountCard(loading: Boolean, listRules: Int, userRules: Int) {
    val format = remember { NumberFormat.getIntegerInstance() }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(R.string.home_rules_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.home_rules_subtitle,
                        format.format(listRules),
                        format.format(userRules),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RecentlyBlockedCard(
    entries: List<dev.muto.app.data.QueryLogRepository.Entry>,
    onOpenLog: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_recent_title), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onOpenLog) { Text(stringResource(R.string.action_see_all)) }
            }

            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.home_recent_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEach { entry ->
                    Text(entry.host, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Says plainly what the active mode cannot do, on the first screen rather than buried in a help
 * page. People install an ad blocker, open YouTube, still see an ad, and conclude the app is
 * broken — it is not, and the reason is worth two sentences.
 */
@Composable
private fun NoticeCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
