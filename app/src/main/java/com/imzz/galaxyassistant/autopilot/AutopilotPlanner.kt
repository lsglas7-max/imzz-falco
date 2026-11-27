package com.imzz.galaxyassistant.autopilot

import android.content.Context
import com.imzz.galaxyassistant.screen.ScreenCaptureService

class AutopilotPlanner(private val context: Context) {
    fun next(goal: String, ocrText: String, layout: String, history: String): PlannedAction {
        val frame = ScreenCaptureService.latestFrame(context)
        return AiVisionApiClient.plan(frame, ocrText, layout, goal, history)
    }
}
