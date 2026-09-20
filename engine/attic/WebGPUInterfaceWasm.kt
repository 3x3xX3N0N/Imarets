package com.example.spacegraphkt.core

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import org.khronos.webgl.*
import kotlin.js.*

/**
 * WASM implementation of WebGPU interface using proper type system
 */
@JsModule("webgpu")
@JsNonModule
external class GPUDevice {
    fun createBuffer(descriptor: dynamic): GPUBuffer
    fun createRenderPipeline(descriptor: dynamic): GPURenderPipeline
    fun createShaderModule(descriptor: dynamic): GPUShaderModule
    fun createCommandEncoder(): GPUCommandEncoder
    fun createBindGroup(descriptor: dynamic): GPUBindGroup
    val queue: GPUQueue
    fun destroy()
}

@JsModule("webgpu")
@JsNonModule
external class GPUBuffer {
    fun getMappedRange(): ArrayBuffer
    fun unmap()
    fun destroy()
}

@JsModule("webgpu")
@JsNonModule
external class GPURenderPipeline {
    fun getBindGroupLayout(index: Int): GPUBindGroupLayout
    fun destroy()
}

@JsModule("webgpu")
@JsNonModule
external class GPUShaderModule

@JsModule("webgpu")
@JsNonModule
external class GPUCommandEncoder {
    fun beginRenderPass(descriptor: dynamic): GPURenderPassEncoder
    fun finish(): GPUCommandBuffer
}

@JsModule("webgpu")
@JsNonModule
external class GPURenderPassEncoder {
    fun setPipeline(pipeline: GPURenderPipeline)
    fun setVertexBuffer(slot: Int, buffer: GPUBuffer)
    fun setBindGroup(index: Int, bindGroup: GPUBindGroup)
    fun draw(vertexCount: Int, instanceCount: Int, firstVertex: Int, firstInstance: Int)
    fun end()
}

@JsModule("webgpu")
@JsNonModule
external class GPUBindGroup

@JsModule("webgpu")
@JsNonModule
external class GPUBindGroupLayout

@JsModule("webgpu")
@JsNonModule
external class GPUCommandBuffer

@JsModule("webgpu")
@JsNonModule
external class GPUQueue {
    fun submit(commandBuffers: Array<GPUCommandBuffer>)
    fun writeBuffer(buffer: GPUBuffer, offset: Int, data: ArrayBuffer)
}

class WebGPUInterfaceWasm : WebGPUInterface {
    internal var device: GPUDevice? = null
    internal var initialized = false
    internal val buffers = Indexed<GPUBuffer?>(0) { null }
    internal val pipelines = Indexed<GPURenderPipeline?>(0) { null }
    internal var nextBufferId = 0
    internal var nextPipelineId = 0
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        try {
            val adapter = js("navigator.gpu.requestAdapter()").await()
            device = adapter.requestDevice().await()
            initialized = true
            true
        } catch (e: dynamic) {
            console.error("Failed to initialize WebGPU:", e)
            false
        }
    }
    
    override fun createVertexBuffer(data: Indexed<VertexData>): BufferId {
        val bufferId = BufferId(nextBufferId++)
        val buffer = device?.createBuffer(js("""
            {
                size: ${data.size * 24}, // 6 floats per vertex (3 position + 3 normal)
                usage: 0x0008 | 0x0010, // VERTEX | COPY_DST
                mappedAtCreation: true
            }
        """)) ?: return bufferId
        
        // Convert Indexed<VertexData> to Float32Array
        val floatData = Float32Array(data.size * 6)
        data.α { vertex ->
            val pos = vertex.a
            val norm = vertex.b
            floatData.set(pos, floatData.length - 6)
            floatData.set(norm, floatData.length - 3)
        }
        
        // Copy data to buffer
        val arrayBuffer = floatData.buffer
        js("new Float32Array(buffer.getMappedRange()).set(floatData)")
        buffer.unmap()
        
        buffers.α { it j buffer }
        return bufferId
    }
    
    override fun createUniformBuffer(data: UniformData): BufferId {
        val bufferId = BufferId(nextBufferId++)
        val buffer = device?.createBuffer(js("""
            {
                size: 64, // 4x4 matrix + 3 floats for camera position
                usage: 0x0002 | 0x0010, // UNIFORM | COPY_DST
                mappedAtCreation: true
            }
        """)) ?: return bufferId
        
        // Convert UniformData to Float32Array
        val floatData = Float32Array(19) // 16 for matrix + 3 for camera
        data.a.data.α { floatData.set(it, floatData.length - 19) }
        data.b.data.α { floatData.set(it, floatData.length - 3) }
        
        // Copy data to buffer
        js("new Float32Array(buffer.getMappedRange()).set(floatData)")
        buffer.unmap()
        
        buffers.α { it j buffer }
        return bufferId
    }
    
    override fun createRenderPipeline(vertexShader: String, fragmentShader: String): PipelineId {
        val pipelineId = PipelineId(nextPipelineId++)
        val pipeline = device?.createRenderPipeline(js("""
            {
                layout: 'auto',
                vertex: {
                    module: device.createShaderModule({
                        code: vertexShader
                    }),
                    entryPoint: 'vs_main',
                    buffers: [{
                        arrayStride: 24,
                        attributes: [
                            {
                                shaderLocation: 0,
                                offset: 0,
                                format: 'float32x3'
                            },
                            {
                                shaderLocation: 1,
                                offset: 12,
                                format: 'float32x3'
                            }
                        ]
                    }]
                },
                fragment: {
                    module: device.createShaderModule({
                        code: fragmentShader
                    }),
                    entryPoint: 'fs_main',
                    targets: [{
                        format: 'bgra8unorm'
                    }]
                },
                primitive: {
                    topology: 'triangle-list'
                },
                depthStencil: {
                    depthWriteEnabled: true,
                    depthCompare: 'less',
                    format: 'depth24plus'
                }
            }
        """)) ?: return pipelineId
        
        pipelines.α { it j pipeline }
        return pipelineId
    }
    
    override fun updateBuffer(bufferId: BufferId, data: ByteArray) {
        val buffer = buffers[bufferId.value] ?: return
        val floatData = Float32Array(data.size / 4)
        data.forEachIndexed { i, byte ->
            floatData[i / 4] = byte.toFloat()
        }
        
        device?.queue?.writeBuffer(buffer, 0, floatData.buffer)
    }
    
    override fun render(renderData: RenderData): RenderResult {
        val vertices = renderData.a
        val uniforms = renderData.b
        val device = device ?: return RenderResult(false, 0f, 0)
        
        // Create command encoder
        val commandEncoder = device.createCommandEncoder()
        val renderPass = commandEncoder.beginRenderPass(js("""
            {
                colorAttachments: [{
                    view: context.getCurrentTexture().createView(),
                    clearValue: { r: 0.0, g: 0.0, b: 0.0, a: 1.0 },
                    loadOp: 'clear',
                    storeOp: 'store'
                }]
            }
        """))
        
        // Set pipeline and draw
        val pipeline = pipelines[0] ?: return RenderResult(false, 0f, 0)
        val vertexBuffer = buffers[0] ?: return RenderResult(false, 0f, 0)
        val uniformBuffer = buffers[1] ?: return RenderResult(false, 0f, 0)
        
        renderPass.setPipeline(pipeline)
        renderPass.setVertexBuffer(0, vertexBuffer)
        renderPass.setBindGroup(0, createBindGroup(pipeline, uniformBuffer))
        renderPass.draw(vertices.size * 3, 1, 0, 0)
        renderPass.end()
        
        // Submit commands
        device.queue.submit(arrayOf(commandEncoder.finish()))
        
        return RenderResult(
            success = true,
            frameTime = 16.67f,
            trianglesRendered = vertices.size
        )
    }
    
    internal fun createBindGroup(pipeline: GPURenderPipeline, uniformBuffer: GPUBuffer): GPUBindGroup {
        return device?.createBindGroup(js("""
            {
                layout: ${pipeline.getBindGroupLayout(0)},
                entries: [{
                    binding: 0,
                    resource: {
                        buffer: uniformBuffer
                    }
                }]
            }
        """)) ?: throw IllegalStateException("Device not initialized")
    }
    
    override fun dispose() {
        buffers.α { buffer ->
            buffer?.destroy()
            null
        }
        pipelines.α { pipeline ->
            pipeline?.destroy()
            null
        }
        device?.destroy()
        initialized = false
    }
} 