package io.github.xiangyuplayer.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xiangyuplayer.R

@Composable
fun HomeScreen(available: Boolean, modifier: Modifier = Modifier, onDaily: () -> Unit, onLogin: () -> Unit) {
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
            Card(onClick = onLogin, enabled = !available, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.personal_fm), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (available) R.string.recommend_pending else R.string.daily_login))
                }
            }
        }
    }
}
