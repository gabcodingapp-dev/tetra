package com.tetra.bot.core

import android.app.Application
import android.content.Context

class TetraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TetraApp
            private set

        fun ctx(): Context = instance
    }
}