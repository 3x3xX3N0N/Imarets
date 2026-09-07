package com.example.spacegraphkt.core

import borg.trikeshed.lib.*

/**
 * Value classes for WebGPU resource IDs
 */
value class BufferId(val value: Int)

value class PipelineId(val value: Int)

value class ShaderId(val value: Int)

value class TextureId(val value: Int)

value class RenderPassId(val value: Int)

/**
 * Type aliases for WebGPU data structures using Indexed and Join
 */
typealias VertexData = Join<Indexed<Float>, Indexed<Float>> // Position and Normal
typealias UniformData = Join<Matrix4, Vector3> // View/Projection Matrix and Camera Position
typealias RenderData = Join<Indexed<VertexData>, Indexed<UniformData>>

/**
 * Matrix4 implementation using Indexed<Float>
 */
class Matrix4(val data: Indexed<Float>) {
    companion object {
        fun identity(): Matrix4 = Matrix4(Indexed(16) { if (it % 5 == 0) 1f else 0f })
    }
}

/**
 * Vector3 implementation using Indexed<Float>
 */
class Vector3(val data: Indexed<Float>) {
    companion object {
        fun zero(): Vector3 = Vector3(Indexed(3) { 0f })
    }
}

/**
 * Result of a render operation
 */
data class RenderResult(
    val success: Boolean,
    val frameTime: Float,
    val trianglesRendered: Int
)

/**
 * Interface for WebGPU operations
 */
interface WebGPUInterface {
    /**
     * Initialize the WebGPU device
     */
    suspend fun initialize(): Boolean

    /**
     * Create a vertex buffer from vertex data
     */
    fun createVertexBuffer(data: Indexed<VertexData>): BufferId

    /**
     * Create a uniform buffer from uniform data
     */
    fun createUniformBuffer(data: UniformData): BufferId

    /**
     * Create a render pipeline with vertex and fragment shaders
     */
    fun createRenderPipeline(vertexShader: String, fragmentShader: String): PipelineId

    /**
     * Update a buffer with new data
     */
    fun updateBuffer(bufferId: BufferId, data: ByteArray)

    /**
     * Render a frame using the provided render data
     */
    fun render(renderData: RenderData): RenderResult

    /**
     * Clean up resources
     */
    fun dispose()
} 