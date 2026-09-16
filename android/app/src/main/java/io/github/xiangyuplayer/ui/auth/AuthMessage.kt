package io.github.xiangyuplayer.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.auth.AuthStage

@Composable
fun AuthMessage(state: AuthState) {
    val message = state.message ?: return
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
            color = if (message == R.string.code_sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        state.diagnostic?.let { diagnostic ->
            val stage = stringResource(when (diagnostic.stage) {
                AuthStage.DEVICE -> R.string.auth_stage_device
                AuthStage.SMS -> R.string.auth_stage_sms
                AuthStage.LOGIN -> R.string.auth_stage_login
                AuthStage.VERIFY -> R.string.auth_stage_verify
                AuthStage.REFRESH -> R.string.auth_stage_refresh
            })
            SelectionContainer {
                Text(
                    if (diagnostic.httpStatus != null) stringResource(R.string.auth_http_diagnostic, stage, diagnostic.httpStatus)
                    else stringResource(R.string.auth_diagnostic, stage,
                        diagnostic.status ?: stringResource(R.string.auth_code_missing),
                        diagnostic.code ?: stringResource(R.string.auth_code_missing)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
