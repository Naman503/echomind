package app.echomind.surfaces

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.echomind.App
import app.echomind.MainActivity
import app.echomind.ai.Prompts
import app.echomind.capture.CaptureController
import app.echomind.capture.CaptureService
import kotlinx.coroutines.flow.first

/** Home-screen widget: status, next reminder, and a tap to start or pause. */
class EchoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val g = App.graph
        val mode = g.capture.state.value.mode
        val next = g.db.tasks().upcomingReminders(System.currentTimeMillis()).firstOrNull()
        val waiting = g.db.tasks().suggestedCount().first()
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(14.dp)
                        .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                ) {
                    Text(
                        when (mode) {
                            CaptureController.Mode.LISTENING -> "● Listening"
                            CaptureController.Mode.PAUSED -> "Paused"
                            CaptureController.Mode.BLOCKED_CALL -> "On a call"
                            CaptureController.Mode.PROCESSING -> "Catching up"
                            CaptureController.Mode.QUIET -> "Quiet hours"
                            else -> "Not listening"
                        },
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    Text(next?.let { "${Prompts.time(it.remindAt!!)} · ${it.title}" } ?: "No reminders coming up",
                        style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 2)
                    if (waiting > 0) Text("$waiting to review", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.primary))
                    Spacer(GlanceModifier.height(8.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val listening = mode == CaptureController.Mode.LISTENING
                        Text(
                            if (listening) "Pause" else "Start listening",
                            style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = GlanceTheme.colors.onPrimaryContainer),
                            modifier = GlanceModifier.background(GlanceTheme.colors.primaryContainer).cornerRadius(16.dp).padding(horizontal = 12.dp, vertical = 6.dp)
                                .clickable(
                                    // A widget tap is one of the few ways Android lets an app start the microphone from the background.
                                    actionStartService(
                                        Intent(context, CaptureService::class.java).setAction(if (listening) CaptureService.ACTION_PAUSE else CaptureService.ACTION_START)
                                            .putExtra(CaptureService.EXTRA_MINUTES, 0L),
                                        isForegroundService = true,
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }
}

class EchoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EchoWidget()
}
