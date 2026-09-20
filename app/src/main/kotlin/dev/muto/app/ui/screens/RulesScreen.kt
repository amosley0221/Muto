package dev.muto.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muto.app.R
import dev.muto.app.data.db.RuleAction
import dev.muto.app.data.db.RuleEntity
import dev.muto.app.ui.MutoViewModel

/**
 * The screen people come to when something is broken. Allow is the first option and the default,
 * because "this site stopped working" is the overwhelmingly common reason to be here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: MutoViewModel) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.ALLOW) }

    val allowRules = rules.filter { it.action == RuleAction.ALLOW }
    val blockRules = rules.filter { it.action == RuleAction.BLOCK }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.rules_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            RuleAction.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = action == option,
                                    onClick = { action = option },
                                    shape = SegmentedButtonDefaults.itemShape(index, RuleAction.entries.size),
                                ) {
                                    Text(
                                        stringResource(
                                            if (option == RuleAction.ALLOW) R.string.rules_allow else R.string.rules_block,
                                        ),
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text(stringResource(R.string.rules_domain_label)) },
                            placeholder = { Text("example.com") },
                            supportingText = { Text(stringResource(R.string.rules_domain_help)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        TextButton(
                            onClick = {
                                viewModel.addRule(draft, action)
                                draft = ""
                            },
                            enabled = draft.isNotBlank(),
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(stringResource(R.string.action_add)) }
                    }
                }
            }

            ruleSection(
                title = stringResource(R.string.rules_allow_section),
                empty = stringResource(R.string.rules_allow_empty),
                rules = allowRules,
                onRemove = viewModel::removeRule,
            )
            ruleSection(
                title = stringResource(R.string.rules_block_section),
                empty = stringResource(R.string.rules_block_empty),
                rules = blockRules,
                onRemove = viewModel::removeRule,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ruleSection(
    title: String,
    empty: String,
    rules: List<RuleEntity>,
    onRemove: (Long) -> Unit,
) {
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (rules.isEmpty()) {
        item {
            Text(
                empty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(rules, key = { it.id }) { rule ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(rule.domain, style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onRemove(rule.id) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.action_remove))
                    }
                }
            }
        }
    }
}
