package com.gaslab.microgas

import android.app.Application
import android.content.Context

class MicroGasDjiApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }
}
