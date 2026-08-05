package id.my.femasaf.secondlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Config.isBotEnabled(context) &&
            Config.hasValidCredentials(context)) {
            
            Log.d("BootReceiver", "Boot completed, starting BotService...")
            val serviceIntent = Intent(context, BotService::class.java)
            
            context.startForegroundService(serviceIntent)
        }
    }
}
