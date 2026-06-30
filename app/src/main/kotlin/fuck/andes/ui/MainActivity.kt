package fuck.andes.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fuck.andes.ui.app.AgentAppRoot
import fuck.andes.ui.app.AgentAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentAppTheme {
                AgentAppRoot()
            }
        }
    }
}
