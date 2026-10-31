package com.botsync.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Sessao.iniciar(this)
    }
}
