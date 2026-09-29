package com.lirica.android.memory.retrieval

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Test
import java.io.File

class OnnxRuntimeAvailabilityTest {
    @Test
    fun testOrtEnvironmentLoadsOrReports() {
        val env = OrtEnvironment.getEnvironment()
        println("OrtEnvironment loaded successfully: $env")

        val modelFile = File("src/main/assets/models/bge-small-zh/model_quantized.onnx")
        if (!modelFile.exists()) {
            println("Model file not found at ${modelFile.absolutePath}")
            return
        }
        val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        println("Session created successfully: $session")
        println("Input names: ${session.inputNames}")
        for (name in session.inputNames) {
            println("Input $name info: ${session.inputInfo[name]}")
        }
        println("Output names: ${session.outputNames}")
        for (name in session.outputNames) {
            println("Output $name info: ${session.outputInfo[name]}")
        }
        session.close()
    }
}
