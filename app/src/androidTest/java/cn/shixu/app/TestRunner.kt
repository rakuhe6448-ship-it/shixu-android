package cn.shixu.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class TestShiXuApp : ShiXuApp() { override val databaseName = "shixu-instrumentation.db" }
class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application = super.newApplication(cl,TestShiXuApp::class.java.name,context)
}
