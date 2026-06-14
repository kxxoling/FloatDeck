package app.floatdeck

import android.app.Application

class FloatDeckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogCollector.init(this)
    }
}
