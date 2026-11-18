package chat.vinlagon.secret

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import chat.vinlagon.secret.ui.AgentHome
import chat.vinlagon.secret.theme.VinlagonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VinlagonTheme { AgentHome() } }
    }
}
