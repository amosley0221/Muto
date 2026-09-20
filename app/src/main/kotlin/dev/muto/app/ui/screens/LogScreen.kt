package dev.muto.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import dev.muto.app.data.QueryLogRepository
import dev.muto.app.data.db.RuleAction
import dev.muto.app.ui.MutoViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live decision log.
 *
 * This is the screen that makes the filter debuggable: you can watch a page load, see exactly
 * which name was refused, and fix it with one tap. Entries come from memory and are lost when
 * the tunnel stops unless history is turned on in settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: MutoViewModel) {
    val entries by viewModel.liveLog.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<QueryLogRepository.Entry?>(null) }

    val visible = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { it.host.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                actions = {
                    IconButton(onClick = viewModel::clearHistory) {
                        Icon(Icons.Filled.DeleteSweep, stringResource(R.string.log_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(stringResource(R.string.log_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (entries.isEmpty()) R.string.log_empty else R.string.log_no_matches,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { "${it.timestamp}-${it.host}" }) { entry ->
                        LogRow(entry = entry, onClick = { selected = entry })
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        EntryActionsDialog(
            entry = entry,
            onDismiss = { selected = null },
            onAllow = {
                viewModel.addRule(entry.host, RuleAction.ALLOW)
                selected = null
            },
            onBlock = {
                viewModel.addRule(entry.host, RuleAction.BLOCK)
                selected = null
            },
        )
    }
}

@Composable
private fun LogRow(entry: QueryLogRepository.Entry, onClick: () -> Unit) {
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (entry.blocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(10.dp),
            ) {}

            Column(Modifier.weight(1f)) {
                Text(entry.host, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                entry.rule?.takeIf { it != entry.host }?.let {
                    Text(
                        stringResource(R.string.log_matched_rule, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Text(
                time.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryActionsDialog(
    entry: QueryLogRepository.Entry,
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.host) },
        text = {
            Text(
                stringResource(
                    if (entry.blocked) R.string.log_detail_blocked else R.string.log_detail_allowed,
                    entry.rule ?: stringResource(R.string.log_detail_no_rule),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = if (entry.blocked) onAllow else onBlock) {
                Text(
                    stringResource(
                        if (entry.blocked) R.string.log_action_allow else R.string.log_action_block,
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
