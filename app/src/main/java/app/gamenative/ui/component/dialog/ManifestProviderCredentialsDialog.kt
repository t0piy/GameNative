package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.utils.ManifestProviderAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ManifestProviderCredentialsDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onSaved: () -> Unit = {},
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(visible) {
        ManifestProviderAuthManager.getCredentials(context)
    }

    var accessToken by remember(visible) {
        mutableStateOf(existing.luaToolsSession?.accessToken.orEmpty())
    }
    var refreshToken by remember(visible) {
        mutableStateOf(existing.luaToolsSession?.refreshToken.orEmpty())
    }
    var hubcapApiKey by remember(visible) {
        mutableStateOf(existing.hubcapApiKey.orEmpty())
    }
    var saving by remember(visible) { mutableStateOf(false) }
    var errorMessage by remember(visible) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (!saving) onDismissRequest()
        },
        title = {
            Text(stringResource(R.string.manifest_provider_credentials_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.manifest_provider_credentials_description))

                NoExtractOutlinedTextField(
                    value = accessToken,
                    onValueChange = { accessToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.manifest_provider_luatools_access_token))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )

                NoExtractOutlinedTextField(
                    value = refreshToken,
                    onValueChange = { refreshToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.manifest_provider_luatools_refresh_token))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )

                NoExtractOutlinedTextField(
                    value = hubcapApiKey,
                    onValueChange = { hubcapApiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.manifest_provider_hubcap_api_key))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )

                errorMessage?.let {
                    Text(it)
                }

                if (saving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.manifest_provider_credentials_saving))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    errorMessage = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                ManifestProviderAuthManager.saveManualCredentials(
                                    context = context,
                                    accessToken = accessToken,
                                    refreshToken = refreshToken,
                                    hubcapApiKey = hubcapApiKey,
                                )
                            }
                            onSaved()
                            onDismissRequest()
                        } catch (error: Exception) {
                            errorMessage = error.message ?: error.javaClass.simpleName
                        } finally {
                            saving = false
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.manifest_provider_credentials_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ManifestProviderAuthManager.clear(context)
                            }
                            accessToken = ""
                            refreshToken = ""
                            hubcapApiKey = ""
                            errorMessage = null
                            onSaved()
                        }
                    },
                ) {
                    Text(stringResource(R.string.manifest_provider_credentials_clear))
                }
                TextButton(
                    enabled = !saving,
                    onClick = onDismissRequest,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
