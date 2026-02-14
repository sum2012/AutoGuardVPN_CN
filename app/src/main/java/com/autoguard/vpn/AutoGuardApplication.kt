package com.autoguard.vpn

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AutoGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 強制設定 App 語言為簡體中文
        // 這樣可以確保介面顯示為簡體中文，不受系統語言影響
        val targetLocales = LocaleListCompat.forLanguageTags("zh-Hans")
        if (AppCompatDelegate.getApplicationLocales() != targetLocales) {
            AppCompatDelegate.setApplicationLocales(targetLocales)
        }
    }
}
