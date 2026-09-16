package io.github.xiangyuplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.xiangyuplayer.data.settings.SettingsStore
import io.github.xiangyuplayer.ui.XiangyuApp
import io.github.xiangyuplayer.ui.theme.XiangyuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsStore(applicationContext)
        setContent {
            XiangyuTheme {
                XiangyuApp(settings)
            }
        }
    }
}
