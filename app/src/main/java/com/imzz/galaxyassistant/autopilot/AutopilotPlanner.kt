package com.imzz.galaxyassistant.autopilot

import android.content.Context
import com.imzz.galaxyassistant.screen.ScreenCaptureService

class AutopilotPlanner(private val context: Context) {
    fun next(goal: String, ocrText: String, layout: String, scene: String, history: String): PlannedAction {
        val frame = ScreenCaptureService.latestFrame(context)
        val allowImage = context.getSharedPreferences("skylen", Context.MODE_PRIVATE).getBoolean("vision_upload", false)
        return AiVisionApiClient.plan(frame, ocrText, layout, scene, goal, history, allowImage)
    }
}
