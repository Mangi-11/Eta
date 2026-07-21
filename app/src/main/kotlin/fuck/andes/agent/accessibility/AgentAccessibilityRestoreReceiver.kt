package fuck.andes.agent.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AgentAccessibilityRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        AgentAccessibilityKeeper.auditAsync(
            context = context,
            reason = action,
            onComplete = pendingResult::finish
        )
    }
}
