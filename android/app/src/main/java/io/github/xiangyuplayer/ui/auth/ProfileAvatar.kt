package io.github.xiangyuplayer.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.remote.AvatarImages

@Composable
fun ProfileAvatar(accountId: String?, url: String?, revision: Int, sessionGeneration: Int, onError: () -> Unit) {
    val context = LocalContext.current
    val loader = remember(accountId, sessionGeneration) { AvatarImages.create(context) }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    Box(contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Default.Person, stringResource(R.string.avatar_placeholder), Modifier.size(32.dp))
        else {
            val request = remember(url, revision) {
                ImageRequest.Builder(context).data(url).memoryCacheKey("$url#$revision").crossfade(true).build()
            }
            SubcomposeAsyncImage(model = request, imageLoader = loader, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, contentDescription = stringResource(R.string.account_avatar),
                loading = { Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } },
                error = { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, Modifier.size(32.dp)) } },
                onError = { onError() })
        }
    }
}
