package com.example.spacegraphkt.core

import com.example.spacegraphkt.data.ForceLayoutSettings
import com.example.spacegraphkt.data.RigidParams
import com.example.spacegraphkt.data.Vector3D
import com.example.spacegraphkt.data.WeldParams
import com.example.spacegraphkt.data.ElasticParams
import bar.verdantbloom.three.jsObject
import kotlinx.browser.window
import kotlin.math.sqrt
import kotlin.random.Random

class ForceLayout constructor(
    val spaceGraph: SpaceGraph,
    config: ForceLayoutSettings? = null
) {
    // bringup: the upstream file used an undeclared TrikeShed `Indexed` + alpha operator; restored to plain lists.
    val nodes: MutableList<BaseNode> = ArrayList()
    val edges: MutableList<Edge> = ArrayList()
    val velocities: MutableMap<String, Vector3D> = HashMap()
    val fixedNodes: MutableSet<BaseNode> = HashSet()

    var isRunning: Boolean = false

    /** false = the host positions nodes itself (SpaceGraphOptions.layoutEnabled); start / kick / runOnce become no-ops. */
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) stop()
        }
    internal var animationFrameId: Int? = null
    internal var energy: Double = Double.POSITIVE_INFINITY
    internal var lastKickTime: Double = 0.0
    internal var autoStopTimeout: Int? = null

    val settings: ForceLayoutSettings = config ?: ForceLayoutSettings()

    init {
        if (settings.defaultElasticStiffness == 0.001 && settings.attraction != 0.001) {
            settings.defaultElasticStiffness = settings.attraction
        }
        if (settings.defaultElasticIdealLength == 200.0 && settings.idealEdgeLength != 200.0) {
            settings.defaultElasticIdealLength = settings.idealEdgeLength
        }
    }

    fun addNode(node: BaseNode) {
        if (nodes.none { it.id == node.id }) {
            nodes.add(node)
            velocities[node.id] = Vector3D(0.0, 0.0, 0.0)
            kick()
        }
    }

    fun removeNode(node: BaseNode) {
        nodes.removeAll { it.id == node.id }
        velocities.remove(node.id)
        fixedNodes.remove(node)
        if (nodes.size < 2 && isRunning) {
            stop()
        } else {
            kick()
        }
    }

    fun addEdge(edge: Edge) {
        if (!edges.contains(edge)) {
            edges.add(edge)
            kick()
        }
    }

    fun removeEdge(edge: Edge) {
        edges.remove(edge)
        kick()
    }

    fun fixNode(node: BaseNode) {
        fixedNodes.add(node)
        velocities[node.id]?.set(0.0, 0.0, 0.0)
    }

    fun releaseNode(node: BaseNode) {
        fixedNodes.remove(node)
    }

    fun runOnce(steps: Int = 100) {
        if (!enabled) return
        console.log("ForceLayout: Running $steps initial stabilization steps...")
        var i = 0
        for (s_i in 0 until steps) {
            i = s_i
            if (_calculateStep() < settings.minEnergyThreshold) break
        }
        console.log("ForceLayout: Initial steps completed after $i iterations.")
        spaceGraph._updateNodesAndEdges()
        spaceGraph.agentApi?.dispatchGraphEvent("layoutStabilized", jsObject { this.steps = i })
    }

    fun start() {
        if (!enabled || isRunning || nodes.size < 2) return
        console.log("ForceLayout: Starting simulation.")
        isRunning = true
        lastKickTime = kotlin.js.Date.now()
        spaceGraph.agentApi?.dispatchGraphEvent("layoutStarted", jsObject {})

        fun loop() {
            if (!isRunning) return
            energy = _calculateStep()
            if (energy < settings.minEnergyThreshold && (kotlin.js.Date.now() - lastKickTime > settings.autoStopDelay)) {
                stop()
                spaceGraph.agentApi?.dispatchGraphEvent("layoutStopped", jsObject { this.reason = "autoStopLowEnergy"; this.energy = energy })
            } else {
                animationFrameId = window.requestAnimationFrame { loop() }
            }
        }
        animationFrameId = window.requestAnimationFrame { loop() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        animationFrameId?.let { window.cancelAnimationFrame(it) }
        autoStopTimeout?.let { window.clearTimeout(it) }
        animationFrameId = null
        autoStopTimeout = null
        console.log("ForceLayout: Simulation stopped. Energy: ${energy.asDynamic().toFixed(4)}")
        spaceGraph.agentApi?.dispatchGraphEvent("layoutStopped", jsObject { this.reason = "manualStop"; this.energy = energy })
    }

    fun kick(intensity: Double = 1.0) {
        if (!enabled || nodes.isEmpty()) return
        lastKickTime = kotlin.js.Date.now()
        energy = Double.POSITIVE_INFINITY

        nodes.forEach { node ->
            if (!fixedNodes.contains(node)) {
                val randomVec = Vector3D(
                    Random.nextDouble() - 0.5,
                    Random.nextDouble() - 0.5,
                    (Random.nextDouble() - 0.5) * settings.zSpreadFactor
                )
                val length = sqrt(randomVec.x*randomVec.x + randomVec.y*randomVec.y + randomVec.z*randomVec.z)
                if (length > 1e-6) {
                    val normFactor = 1.0 / length
                    randomVec.x *= normFactor
                    randomVec.y *= normFactor
                    randomVec.z *= normFactor
                }

                val kickStrength = intensity * (1 + Random.nextDouble() * 2)
                velocities[node.id]?.let { vel ->
                    vel.x += randomVec.x * kickStrength
                    vel.y += randomVec.y * kickStrength
                    vel.z += randomVec.z * kickStrength
                }
            }
        }

        if (!isRunning) start()

        autoStopTimeout?.let { window.clearTimeout(it) }
        autoStopTimeout = window.setTimeout({
            if (isRunning && energy < settings.minEnergyThreshold) {
                stop()
                spaceGraph.agentApi?.dispatchGraphEvent("layoutStopped", jsObject { this.reason = "autoStopLowEnergyAfterKick"; this.energy = energy })
            }
        }, settings.autoStopDelay.toInt())
        spaceGraph.agentApi?.dispatchGraphEvent("layoutKicked", jsObject { this.intensity = intensity })
    }

    fun setSettings(newSettings: ForceLayoutSettings) {
        settings.repulsion = newSettings.repulsion
        settings.attraction = newSettings.attraction
        settings.idealEdgeLength = newSettings.idealEdgeLength
        settings.centerStrength = newSettings.centerStrength
        settings.damping = newSettings.damping
        settings.minEnergyThreshold = newSettings.minEnergyThreshold
        settings.gravityCenter.copy(newSettings.gravityCenter)
        settings.zSpreadFactor = newSettings.zSpreadFactor
        settings.autoStopDelay = newSettings.autoStopDelay
        settings.nodePadding = newSettings.nodePadding
        settings.defaultElasticStiffness = newSettings.defaultElasticStiffness
        settings.defaultElasticIdealLength = newSettings.defaultElasticIdealLength
        settings.defaultRigidStiffness = newSettings.defaultRigidStiffness
        settings.defaultWeldStiffness = newSettings.defaultWeldStiffness

        console.log("ForceLayout settings updated: $settings")
        kick()
        spaceGraph.agentApi?.dispatchGraphEvent("layoutSettingsChanged", settingsToJsObject(settings))
    }

    internal fun settingsToJsObject(s: ForceLayoutSettings): dynamic {
        return jsObject {
            this.repulsion = s.repulsion
            this.attraction = s.attraction
            this.idealEdgeLength = s.idealEdgeLength
            this.centerStrength = s.centerStrength
            this.damping = s.damping
            this.minEnergyThreshold = s.minEnergyThreshold
            this.gravityCenter = s.gravityCenter.asDynamic()
            this.zSpreadFactor = s.zSpreadFactor
            this.autoStopDelay = s.autoStopDelay
            this.nodePadding = s.nodePadding
            this.defaultElasticStiffness = s.defaultElasticStiffness
            this.defaultElasticIdealLength = s.defaultElasticIdealLength
            this.defaultRigidStiffness = s.defaultRigidStiffness
            this.defaultWeldStiffness = s.defaultWeldStiffness
        }
    }

    fun _calculateStep(): Double {
        if (nodes.size < 2 && edges.isEmpty() && settings.centerStrength <= 0) return 0.0

        var totalSystemEnergy = 0.0
        val forces = HashMap<String, Vector3D>()
        nodes.forEach { node -> forces[node.id] = Vector3D(0.0, 0.0, 0.0) }

        val tempDelta = Vector3D(0.0, 0.0, 0.0)

        for (i in 0 until nodes.size) {
            val nodeA = nodes[i]
            for (j in i + 1 until nodes.size) {
                val nodeB = nodes[j]

                tempDelta.x = nodeB.position.x - nodeA.position.x
                tempDelta.y = nodeB.position.y - nodeA.position.y
                tempDelta.z = nodeB.position.z - nodeA.position.z

                var distSq = tempDelta.x * tempDelta.x + tempDelta.y * tempDelta.y + tempDelta.z * tempDelta.z
                if (distSq < 1e-4) {
                    distSq = 1e-4
                    tempDelta.x = (Random.nextDouble() - 0.5) * 0.01
                    tempDelta.y = (Random.nextDouble() - 0.5) * 0.01
                    tempDelta.z = (Random.nextDouble() - 0.5) * 0.01 * settings.zSpreadFactor
                }
                val dist = sqrt(distSq)

                var forceMag = -settings.repulsion / distSq

                val radiusA = nodeA.getBoundingSphereRadius() * settings.nodePadding
                val radiusB = nodeB.getBoundingSphereRadius() * settings.nodePadding
                val combinedRadius = radiusA + radiusB
                val overlap = combinedRadius - dist
                if (overlap > 0) {
                    forceMag -= settings.repulsion * (overlap * overlap) * 0.01 / dist
                }

                val normFactor = 1.0 / dist
                val forceVecX = tempDelta.x * normFactor * forceMag
                val forceVecY = tempDelta.y * normFactor * forceMag
                val forceVecZ = tempDelta.z * normFactor * forceMag * settings.zSpreadFactor

                if (!fixedNodes.contains(nodeA)) {
                    forces[nodeA.id]?.let { f ->
                        f.x += forceVecX
                        f.y += forceVecY
                        f.z += forceVecZ
                    }
                }
                if (!fixedNodes.contains(nodeB)) {
                    forces[nodeB.id]?.let { f ->
                        f.x -= forceVecX
                        f.y -= forceVecY
                        f.z -= forceVecZ
                    }
                }
            }
        }

        edges.forEach { edge ->
            val source = edge.source
            val target = edge.target

            tempDelta.x = target.position.x - source.position.x
            tempDelta.y = target.position.y - source.position.y
            tempDelta.z = target.position.z - source.position.z

            val distance = sqrt(tempDelta.x*tempDelta.x + tempDelta.y*tempDelta.y + tempDelta.z*tempDelta.z) + 1e-6
            val normFactor = 1.0 / distance
            var forceMag = 0.0

            val params = edge.data.constraintParams
            when (edge.data.constraintType) {
                "rigid" -> {
                    val rParams = params as? RigidParams ?: RigidParams(stiffness = settings.defaultRigidStiffness)
                    val targetDist = rParams.distance ?: source.position.distanceTo(target.position)
                    forceMag = rParams.stiffness * (distance - targetDist)
                }
                "weld" -> {
                    val wParams = params as? WeldParams ?: WeldParams(stiffness = settings.defaultWeldStiffness)
                    val weldDist = wParams.distance ?: (source.getBoundingSphereRadius() + target.getBoundingSphereRadius())
                    forceMag = wParams.stiffness * (distance - weldDist)
                }
                else -> { // elastic
                    val eParams = params as? ElasticParams ?: ElasticParams(stiffness = settings.defaultElasticStiffness, idealLength = settings.defaultElasticIdealLength)
                    forceMag = eParams.stiffness * (distance - eParams.idealLength)
                }
            }

            val forceVecX = tempDelta.x * normFactor * forceMag
            val forceVecY = tempDelta.y * normFactor * forceMag
            val forceVecZ = tempDelta.z * normFactor * forceMag * settings.zSpreadFactor

            if (!fixedNodes.contains(source)) {
                forces[source.id]?.let { f ->
                    f.x += forceVecX
                    f.y += forceVecY
                    f.z += forceVecZ
                }
            }
            if (!fixedNodes.contains(target)) {
                forces[target.id]?.let { f ->
                    f.x -= forceVecX
                    f.y -= forceVecY
                    f.z -= forceVecZ
                }
            }
        }

        if (settings.centerStrength > 0) {
            nodes.forEach { node ->
                if (fixedNodes.contains(node)) return@forEach

                tempDelta.x = settings.gravityCenter.x - node.position.x
                tempDelta.y = settings.gravityCenter.y - node.position.y
                tempDelta.z = settings.gravityCenter.z - node.position.z

                val forceVecX = tempDelta.x * settings.centerStrength
                val forceVecY = tempDelta.y * settings.centerStrength
                val forceVecZ = tempDelta.z * settings.centerStrength * settings.zSpreadFactor * 0.5

                forces[node.id]?.let { f ->
                    f.x += forceVecX
                    f.y += forceVecY
                    f.z += forceVecZ
                }
            }
        }

        nodes.forEach { node ->
            if (fixedNodes.contains(node)) return@forEach

            val force = forces[node.id] ?: return@forEach
            val velocity = velocities[node.id] ?: return@forEach
            val mass = node.mass.coerceAtLeast(0.1)

            val accelX = force.x / mass
            val accelY = force.y / mass
            val accelZ = force.z / mass

            velocity.x = (velocity.x + accelX) * settings.damping
            velocity.y = (velocity.y + accelY) * settings.damping
            velocity.z = (velocity.z + accelZ) * settings.damping

            val speedSq = velocity.x * velocity.x + velocity.y * velocity.y + velocity.z * velocity.z
            val maxSpeed = 50.0
            if (speedSq > maxSpeed * maxSpeed) {
                val speed = sqrt(speedSq)
                val limitFactor = maxSpeed / speed
                velocity.x *= limitFactor
                velocity.y *= limitFactor
                velocity.z *= limitFactor
            }

            node.position.x += velocity.x
            node.position.y += velocity.y
            node.position.z += velocity.z

            totalSystemEnergy += 0.5 * mass * (velocity.x*velocity.x + velocity.y*velocity.y + velocity.z*velocity.z)
        }
        return totalSystemEnergy
    }

    fun dispose() {
        stop()
        nodes.clear()
        edges.clear()
        velocities.clear()
        fixedNodes.clear()
        console.log("ForceLayout disposed.")
    }
}

