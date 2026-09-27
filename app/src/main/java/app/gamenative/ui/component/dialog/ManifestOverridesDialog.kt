package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.utils.ManifestOverrideSourceKind
import app.gamenative.utils.SteamManifestOverride
import app.gamenative.utils.StorageUtils
import java.text.DateFormat
import java.util.Date

@Composable
fun ManifestOverridesDialog(
    visible: Boolean,
    overrides: List<SteamManifestOverride>,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.manifest_overrides_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.manifest_overrides_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (overrides.isEmpty()) {
                    Text(stringResource(R.string.manifest_overrides_empty))
                } else {
                    overrides.sortedBy { it.depotId }.forEachIndexed { index, override ->
                        if (index > 0) {
                            HorizontalDivider()
                        }

                        Text(
                            text = stringResource(
                                R.string.manifest_override_depot,
                                override.depotId,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(
                                R.string.manifest_override_gid,
                                java.lang.Long.toUnsignedString(override.manifestId),
                            ),
                        )

                        override.sizeOnDisk?.takeIf { it > 0L }?.let { size ->
                            Text(
                                stringResource(
                                    R.string.manifest_override_size,
                                    StorageUtils.formatBinarySize(size),
                                ),
                            )
                        }

                        val provenance = override.provenance
                        val kindLabel = when (provenance?.sourceKind) {
                            ManifestOverrideSourceKind.LocalFile ->
                                stringResource(R.string.manifest_override_source_local)
                            ManifestOverrideSourceKind.RemoteUrl ->
                                stringResource(R.string.manifest_override_source_url)
                            ManifestOverrideSourceKind.DirectProvider ->
                                stringResource(R.string.manifest_override_source_direct)
                            ManifestOverrideSourceKind.LuaToolsProvider ->
                                stringResource(R.string.manifest_override_source_luatools)
                            ManifestOverrideSourceKind.Hubcap ->
                                stringResource(R.string.manifest_override_source_hubcap)
                            ManifestOverrideSourceKind.Unknown,
                            null,
                            -> stringResource(R.string.manifest_override_source_unknown)
                        }
                        val sourceText = provenance?.sourceLabel
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "$kindLabel · $it" }
                            ?: kindLabel
                        Text(
                            stringResource(
                                R.string.manifest_override_source,
                                sourceText,
                            ),
                        )

                        provenance?.importedAtEpochMillis
                            ?.takeIf { it > 0L }
                            ?.let { importedAt ->
                                Text(
                                    stringResource(
                                        R.string.manifest_override_imported_at,
                                        DateFormat.getDateTimeInstance(
                                            DateFormat.MEDIUM,
                                            DateFormat.SHORT,
                                        ).format(Date(importedAt)),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.acknowledge))
            }
        },
    )
}
