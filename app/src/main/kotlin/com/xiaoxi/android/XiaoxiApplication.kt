package com.xiaoxi.android

import android.app.Application
import com.xiaoxi.android.database.XiaoxiDatabase
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware

class XiaoxiApplication : Application() {

    lateinit var database: XiaoxiDatabase
        private set

    lateinit var memoryManager: DualTrackMemoryManager
        private set

    lateinit var emotionEngine: EmotionEngine
        private set

    lateinit var llmClient: XiaoxiLlmClient
        private set

    lateinit var phiMiddleware: PhiMiddleware
        private set

    lateinit var humannessFilter: HumannessFilter
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = XiaoxiDatabase.getInstance(this)
        memoryManager = DualTrackMemoryManager(database)
        emotionEngine = EmotionEngine()
        llmClient = XiaoxiLlmClient()
        phiMiddleware = PhiMiddleware()
        humannessFilter = HumannessFilter()
    }

    companion object {
        lateinit var instance: XiaoxiApplication
            private set
    }
}
