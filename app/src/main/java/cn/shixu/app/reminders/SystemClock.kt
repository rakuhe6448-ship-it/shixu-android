package cn.shixu.app.reminders

import android.content.Intent
import android.provider.AlarmClock
import cn.shixu.app.ai.PhoneAlarmSpec
import java.time.LocalTime

object SystemClock {
    fun createIntent(alarm: PhoneAlarmSpec): Intent {
        alarm.checkNextOccurrence()
        val time=LocalTime.parse(alarm.time)
        return Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR,time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES,time.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE,alarm.label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI,false)
            .apply {
                // Internal ISO weekdays (Monday=1) -> java.util.Calendar (Sunday=1).
                if(alarm.days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS,ArrayList(alarm.days.map { it%7+1 }))
            }
    }
    fun showIntent()=Intent(AlarmClock.ACTION_SHOW_ALARMS)
}
