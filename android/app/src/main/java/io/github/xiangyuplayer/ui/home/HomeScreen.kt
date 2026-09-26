package io.github.xiangyuplayer.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.playback.FmStatus
import io.github.xiangyuplayer.ui.playback.fmMessage

@Composable
fun HomeScreen(available: Boolean, fmStatus: FmStatus, fmActive: Boolean, connected: Boolean, modifier: Modifier = Modifier, onDaily: () -> Unit, onLogin: () -> Unit, onFm: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Card(onClick = if (available) onDaily else onLogin, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.daily_recommend), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (available) R.string.daily_open else R.string.daily_login))
                }
            }
        }
        item {
            Card(onClick = if (available) onFm else onLogin,
                enabled = !available || (connected && fmStatus != FmStatus.STARTING), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.personal_fm), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (!available) R.string.daily_login else fmMessage(fmStatus)
                        ?: if (fmActive) R.string.fm_open else R.string.fm_start))
                }
            }
        }
    }
}
