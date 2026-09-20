@file:Suppress("unused", "PropertyName", "FunctionName")

package bar.verdantbloom.three

import org.khronos.webgl.Float32Array
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise

/*
 * Kotlin externals for three.js (npm `three`, version pinned in gradle.properties: npm.three).
 *
 * SINGLE IMPORT SOURCE: everything core comes from "three/webgpu" (build/three.webgpu.js). That build
 * re-exports the whole core AND WebGPURenderer + node materials. We never import bare "three" from Kotlin.
 * Addons (CSS3DRenderer, Line2...) import bare "three" internally, so :site aliases `three$` to the same
 * three.webgpu.js file in webpack.config.d/three-single-copy.js -> exactly one copy in the bundle.
 * NOTE: three.webgpu.js does NOT export WebGLRenderer. The webgl rung is WebGPURenderer(forceWebGL = true).
 *
 * Need something that is not here? Declare it in YOUR module (this one is frozen), e.g.
 *   @JsModule("three/webgpu") external object THREEX { class TorusKnotGeometry(...) : THREE.BufferGeometry }
 *   @file:JsModule("three/tsl") for TSL nodes
 * and never with @JsModule("three").
 */
@JsModule("three/webgpu")
external object THREE {
    val REVISION: String

    // ---------------------------------------------------------------- core
    open class EventDispatcher {
        fun addEventListener(type: String, listener: (event: dynamic) -> Unit)
        fun hasEventListener(type: String, listener: (event: dynamic) -> Unit): Boolean
        fun removeEventListener(type: String, listener: (event: dynamic) -> Unit)
        fun dispatchEvent(event: dynamic)
    }

    open class Object3D : EventDispatcher {
        val isObject3D: Boolean
        var id: Int
        var uuid: String
        var name: String
        var type: String
        var parent: Object3D?
        var children: Array<Object3D>
        var up: Vector3
        val position: Vector3
        val rotation: Euler
        val quaternion: Quaternion
        val scale: Vector3
        var matrix: Matrix4
        var matrixWorld: Matrix4
        var matrixAutoUpdate: Boolean
        var matrixWorldNeedsUpdate: Boolean
        var layers: dynamic
        var visible: Boolean
        var castShadow: Boolean
        var receiveShadow: Boolean
        var frustumCulled: Boolean
        var renderOrder: Int
        var userData: dynamic

        fun add(vararg obj: Object3D): Object3D
        fun remove(vararg obj: Object3D): Object3D
        fun removeFromParent(): Object3D
        fun clear(): Object3D
        fun attach(obj: Object3D): Object3D
        fun getObjectByName(name: String): Object3D?
        fun traverse(callback: (obj: Object3D) -> Unit)
        fun traverseVisible(callback: (obj: Object3D) -> Unit)
        fun getWorldPosition(target: Vector3): Vector3
        fun getWorldQuaternion(target: Quaternion): Quaternion
        fun getWorldScale(target: Vector3): Vector3
        fun getWorldDirection(target: Vector3): Vector3
        fun lookAt(vector: Vector3)
        fun lookAt(x: Double, y: Double, z: Double)
        fun applyMatrix4(m: Matrix4)
        fun applyQuaternion(q: Quaternion): Object3D
        fun rotateX(angle: Double): Object3D
        fun rotateY(angle: Double): Object3D
        fun rotateZ(angle: Double): Object3D
        fun rotateOnAxis(axis: Vector3, angle: Double): Object3D
        fun translateX(d: Double): Object3D
        fun translateY(d: Double): Object3D
        fun translateZ(d: Double): Object3D
        fun localToWorld(v: Vector3): Vector3
        fun worldToLocal(v: Vector3): Vector3
        fun updateMatrix()
        fun updateMatrixWorld(force: Boolean = definedExternally)
        fun updateWorldMatrix(updateParents: Boolean, updateChildren: Boolean)
    }

    open class Group : Object3D

    open class Scene : Object3D {
        var background: dynamic // Color, Texture or null
        var environment: dynamic
        var fog: dynamic // Fog, FogExp2 or null
        var backgroundBlurriness: Double
        var backgroundIntensity: Double
        var overrideMaterial: Material?
    }

    class Fog(color: dynamic, near: Number = definedExternally, far: Number = definedExternally) {
        var color: Color
        var near: Double
        var far: Double
    }

    class FogExp2(color: dynamic, density: Number = definedExternally) {
        var color: Color
        var density: Double
    }

    class Clock(autoStart: Boolean = definedExternally) {
        fun start()
        fun stop()
        fun getElapsedTime(): Double
        fun getDelta(): Double
    }

    // ---------------------------------------------------------------- cameras
    open class Camera : Object3D {
        var matrixWorldInverse: Matrix4
        var projectionMatrix: Matrix4
        var projectionMatrixInverse: Matrix4
    }

    open class PerspectiveCamera(
        fov: Number = definedExternally,
        aspect: Number = definedExternally,
        near: Number = definedExternally,
        far: Number = definedExternally,
    ) : Camera {
        var fov: Number
        var aspect: Number
        var near: Number
        var far: Number
        var zoom: Number
        var focus: Number
        fun updateProjectionMatrix()
        fun setViewOffset(fullWidth: Number, fullHeight: Number, x: Number, y: Number, width: Number, height: Number)
        fun clearViewOffset()
        fun getEffectiveFOV(): Double
    }

    open class OrthographicCamera(
        left: Number = definedExternally,
        right: Number = definedExternally,
        top: Number = definedExternally,
        bottom: Number = definedExternally,
        near: Number = definedExternally,
        far: Number = definedExternally,
    ) : Camera {
        var left: Number
        var right: Number
        var top: Number
        var bottom: Number
        var near: Number
        var far: Number
        var zoom: Number
        fun updateProjectionMatrix()
    }

    // ---------------------------------------------------------------- renderer
    /**
     * The one renderer for both GPU rungs. `WebGPURenderer(jsObject { forceWebGL = true })` = WebGL2 backend.
     * MUST `init()` (await the Promise) before the first `render()`. If WebGPU is unavailable three itself
     * falls back to WebGL2; check `backend.isWebGPUBackend` to know which one you got.
     * Parameters (all optional): canvas, antialias, alpha, forceWebGL, powerPreference, samples, logarithmicDepthBuffer.
     */
    class WebGPURenderer(parameters: dynamic = definedExternally) {
        val isWebGPURenderer: Boolean
        val domElement: HTMLCanvasElement
        val backend: dynamic
        var toneMapping: Int
        var toneMappingExposure: Double
        var outputColorSpace: String
        var sortObjects: Boolean
        var autoClear: Boolean
        val shadowMap: ShadowMap
        val info: dynamic
        fun init(): Promise<dynamic>
        fun render(scene: Object3D, camera: Camera)
        fun renderAsync(scene: Object3D, camera: Camera): Promise<Unit>
        fun setAnimationLoop(callback: ((time: Double) -> Unit)?): Promise<Unit>
        fun setSize(width: Number, height: Number, updateStyle: Boolean = definedExternally)
        fun setPixelRatio(value: Number)
        fun getPixelRatio(): Double
        fun getSize(target: Vector2): Vector2
        fun getDrawingBufferSize(target: Vector2): Vector2
        fun setViewport(x: Number, y: Number, width: Number, height: Number)
        fun setScissor(x: Number, y: Number, width: Number, height: Number)
        fun setScissorTest(enable: Boolean)
        fun setClearColor(color: dynamic, alpha: Number = definedExternally)
        fun setClearAlpha(alpha: Number)
        fun clear(color: Boolean = definedExternally, depth: Boolean = definedExternally, stencil: Boolean = definedExternally)
        fun setRenderTarget(target: RenderTarget?)
        fun getRenderTarget(): RenderTarget?
        fun dispose()
    }

    interface ShadowMap {
        var enabled: Boolean
        var type: Int
    }

    open class RenderTarget(width: Number = definedExternally, height: Number = definedExternally, options: dynamic = definedExternally) : EventDispatcher {
        var width: Int
        var height: Int
        var texture: Texture
        fun setSize(width: Number, height: Number, depth: Number = definedExternally)
        fun dispose()
    }

    /** Node-based post-processing chain (bloom, etc.). Configure `outputNode` with TSL from "three/tsl". */
    class PostProcessing(renderer: WebGPURenderer, outputNode: dynamic = definedExternally) {
        var outputNode: dynamic
        var needsUpdate: Boolean
        fun render()
        fun renderAsync(): Promise<Unit>
        fun dispose()
    }

    // ---------------------------------------------------------------- math
    class Vector2(x: Number = definedExternally, y: Number = definedExternally) {
        var x: Double
        var y: Double
        fun set(x: Double, y: Double): Vector2
        fun copy(v: Vector2): Vector2
        fun clone(): Vector2
        fun add(v: Vector2): Vector2
        fun sub(v: Vector2): Vector2
        fun multiplyScalar(s: Double): Vector2
        fun length(): Double
        fun lengthSq(): Double
        fun normalize(): Vector2
        fun distanceTo(v: Vector2): Double
    }

    class Vector3(x: Number = definedExternally, y: Number = definedExternally, z: Number = definedExternally) {
        var x: Double
        var y: Double
        var z: Double
        fun set(x: Double, y: Double, z: Double): Vector3
        fun setScalar(s: Double): Vector3
        fun copy(v: Vector3): Vector3
        fun clone(): Vector3
        fun add(v: Vector3): Vector3
        fun addVectors(a: Vector3, b: Vector3): Vector3
        fun addScaledVector(v: Vector3, s: Double): Vector3
        fun sub(v: Vector3): Vector3
        fun subVectors(a: Vector3, b: Vector3): Vector3
        fun multiply(v: Vector3): Vector3
        fun multiplyScalar(s: Double): Vector3
        fun divideScalar(s: Double): Vector3
        fun negate(): Vector3
        fun dot(v: Vector3): Double
        fun cross(v: Vector3): Vector3
        fun crossVectors(a: Vector3, b: Vector3): Vector3
        fun length(): Double
        fun lengthSq(): Double
        fun setLength(l: Double): Vector3
        fun normalize(): Vector3
        fun distanceTo(v: Vector3): Double
        fun distanceToSquared(v: Vector3): Double
        fun applyMatrix4(m: Matrix4): Vector3
        fun applyQuaternion(q: Quaternion): Vector3
        fun applyAxisAngle(axis: Vector3, angle: Double): Vector3
        fun transformDirection(m: Matrix4): Vector3
        /** World space -> normalized device coordinates (-1..1). */
        fun project(camera: Camera): Vector3
        fun unproject(camera: Camera): Vector3
        fun lerp(v: Vector3, alpha: Double): Vector3
        fun lerpVectors(v1: Vector3, v2: Vector3, alpha: Double): Vector3
        fun equals(v: Vector3): Boolean
        fun setFromMatrixPosition(m: Matrix4): Vector3
        fun setFromMatrixColumn(m: Matrix4, index: Int): Vector3
        fun setFromSphericalCoords(radius: Double, phi: Double, theta: Double): Vector3
        fun clampLength(min: Double, max: Double): Vector3
        fun angleTo(v: Vector3): Double
        fun fromArray(array: dynamic, offset: Int = definedExternally): Vector3
        fun toArray(array: dynamic = definedExternally, offset: Int = definedExternally): dynamic
        fun randomDirection(): Vector3
    }

    class Vector4(x: Number = definedExternally, y: Number = definedExternally, z: Number = definedExternally, w: Number = definedExternally) {
        var x: Double
        var y: Double
        var z: Double
        var w: Double
        fun set(x: Double, y: Double, z: Double, w: Double): Vector4
    }

    /** three.js order is (x, y, z, w) - scalar LAST. bloom-api Quat is scalar FIRST. */
    class Quaternion(x: Number = definedExternally, y: Number = definedExternally, z: Number = definedExternally, w: Number = definedExternally) {
        var x: Double
        var y: Double
        var z: Double
        var w: Double
        fun set(x: Double, y: Double, z: Double, w: Double): Quaternion
        fun copy(q: Quaternion): Quaternion
        fun clone(): Quaternion
        fun identity(): Quaternion
        fun setFromEuler(euler: Euler, update: Boolean = definedExternally): Quaternion
        fun setFromAxisAngle(axis: Vector3, angle: Double): Quaternion
        fun setFromUnitVectors(from: Vector3, to: Vector3): Quaternion
        fun setFromRotationMatrix(m: Matrix4): Quaternion
        fun multiply(q: Quaternion): Quaternion
        fun premultiply(q: Quaternion): Quaternion
        fun multiplyQuaternions(a: Quaternion, b: Quaternion): Quaternion
        fun invert(): Quaternion
        fun conjugate(): Quaternion
        fun normalize(): Quaternion
        fun slerp(qb: Quaternion, t: Double): Quaternion
        fun angleTo(q: Quaternion): Double
    }

    class Euler(x: Number = definedExternally, y: Number = definedExternally, z: Number = definedExternally, order: String = definedExternally) {
        var x: Double
        var y: Double
        var z: Double
        var order: String
        fun set(x: Double, y: Double, z: Double, order: String = definedExternally): Euler
        fun copy(euler: Euler): Euler
        fun setFromQuaternion(q: Quaternion, order: String = definedExternally, update: Boolean = definedExternally): Euler
    }

    class Matrix4 {
        val elements: Array<Double>
        fun identity(): Matrix4
        fun copy(m: Matrix4): Matrix4
        fun clone(): Matrix4
        fun multiply(m: Matrix4): Matrix4
        fun premultiply(m: Matrix4): Matrix4
        fun multiplyMatrices(a: Matrix4, b: Matrix4): Matrix4
        fun invert(): Matrix4
        fun transpose(): Matrix4
        fun lookAt(eye: Vector3, target: Vector3, up: Vector3): Matrix4
        fun makeRotationFromQuaternion(q: Quaternion): Matrix4
        fun makeTranslation(x: Double, y: Double, z: Double): Matrix4
        fun makeScale(x: Double, y: Double, z: Double): Matrix4
        fun compose(translation: Vector3, rotation: Quaternion, scale: Vector3): Matrix4
        fun decompose(translation: Vector3, rotation: Quaternion, scale: Vector3): Matrix4
    }

    class Color(r: dynamic = definedExternally, g: Number = definedExternally, b: Number = definedExternally) {
        var r: Double
        var g: Double
        var b: Double
        fun set(value: dynamic): Color // number, css string or Color
        fun setHex(hex: Number, colorSpace: String = definedExternally): Color
        fun setRGB(r: Double, g: Double, b: Double, colorSpace: String = definedExternally): Color
        fun setHSL(h: Double, s: Double, l: Double, colorSpace: String = definedExternally): Color
        fun setStyle(style: String): Color
        fun copy(color: Color): Color
        fun clone(): Color
        fun getHex(): Int
        fun getHexString(): String
        fun lerp(color: Color, alpha: Double): Color
        fun lerpColors(a: Color, b: Color, alpha: Double): Color
        fun multiplyScalar(s: Double): Color
        fun equals(c: Color): Boolean
    }

    class Box3(min: Vector3 = definedExternally, max: Vector3 = definedExternally) {
        var min: Vector3
        var max: Vector3
        fun setFromObject(obj: Object3D): Box3
        fun getCenter(target: Vector3): Vector3
        fun getSize(target: Vector3): Vector3
    }

    class Sphere(center: Vector3 = definedExternally, radius: Number = definedExternally) {
        var center: Vector3
        var radius: Double
    }

    class Ray(origin: Vector3 = definedExternally, direction: Vector3 = definedExternally) {
        var origin: Vector3
        var direction: Vector3
        fun at(t: Double, target: Vector3): Vector3
        fun intersectPlane(plane: Plane, target: Vector3): Vector3?
        fun distanceSqToSegment(v0: Vector3, v1: Vector3, optionalPointOnRay: Vector3 = definedExternally, optionalPointOnSegment: Vector3 = definedExternally): Double
        fun distanceToPoint(point: Vector3): Double
    }

    class Plane(normal: Vector3 = definedExternally, constant: Number = definedExternally) {
        var normal: Vector3
        var constant: Double
        fun setFromNormalAndCoplanarPoint(normal: Vector3, point: Vector3): Plane
        fun distanceToPoint(point: Vector3): Double
    }

    object MathUtils {
        val DEG2RAD: Double
        val RAD2DEG: Double
        fun clamp(value: Double, min: Double, max: Double): Double
        fun lerp(x: Double, y: Double, t: Double): Double
        fun degToRad(degrees: Double): Double
        fun radToDeg(radians: Double): Double
        fun smoothstep(x: Double, min: Double, max: Double): Double
    }

    // ---------------------------------------------------------------- raycasting
    class Raycaster(origin: Vector3 = definedExternally, direction: Vector3 = definedExternally, near: Number = definedExternally, far: Number = definedExternally) {
        var ray: Ray
        var near: Double
        var far: Double
        var camera: Camera
        /** e.g. `params.Line.threshold = 0.05`, `params.Points.threshold = ...` */
        var params: dynamic
        fun set(origin: Vector3, direction: Vector3)
        /** [coords] are normalized device coordinates (-1..1, y up). */
        fun setFromCamera(coords: Vector2, camera: Camera)
        fun intersectObject(obj: Object3D, recursive: Boolean = definedExternally): Array<Intersection>
        fun intersectObjects(objects: Array<out Object3D>, recursive: Boolean = definedExternally): Array<Intersection>
    }

    interface Intersection {
        val distance: Double
        val point: Vector3
        val index: Int?
        val faceIndex: Int?

        @JsName("object")
        val obj: Object3D
    }

    // ---------------------------------------------------------------- geometry
    open class BufferGeometry : EventDispatcher {
        var id: Int
        var uuid: String
        var name: String
        val attributes: dynamic
        var index: BufferAttribute?
        var boundingBox: Box3?
        var boundingSphere: Sphere?
        var drawRange: dynamic // { start, count }
        var userData: dynamic
        fun getAttribute(name: String): BufferAttribute
        fun setAttribute(name: String, attribute: BufferAttribute): BufferGeometry
        fun deleteAttribute(name: String): BufferGeometry
        fun hasAttribute(name: String): Boolean
        fun setIndex(index: dynamic): BufferGeometry
        fun setDrawRange(start: Int, count: Int)
        fun setFromPoints(points: Array<Vector3>): BufferGeometry
        fun computeBoundingBox()
        fun computeBoundingSphere()
        fun computeVertexNormals()
        fun center(): BufferGeometry
        fun translate(x: Double, y: Double, z: Double): BufferGeometry
        fun scale(x: Double, y: Double, z: Double): BufferGeometry
        fun rotateX(a: Double): BufferGeometry
        fun rotateY(a: Double): BufferGeometry
        fun rotateZ(a: Double): BufferGeometry
        fun clone(): BufferGeometry
        fun dispose()
    }

    /** [array] is a typed array (Float32Array...). A Kotlin FloatArray IS a Float32Array at runtime: pass `floats.asDynamic()`. */
    open class BufferAttribute(array: dynamic = definedExternally, itemSize: Int = definedExternally, normalized: Boolean = definedExternally) {
        var array: dynamic
        val count: Int
        val itemSize: Int
        var needsUpdate: Boolean
        var normalized: Boolean
        var usage: Int
        fun setUsage(usage: Int): BufferAttribute
        fun getX(index: Int): Double
        fun getY(index: Int): Double
        fun getZ(index: Int): Double
        fun setX(index: Int, x: Double): BufferAttribute
        fun setY(index: Int, y: Double): BufferAttribute
        fun setZ(index: Int, z: Double): BufferAttribute
        fun setXY(index: Int, x: Double, y: Double): BufferAttribute
        fun setXYZ(index: Int, x: Double, y: Double, z: Double): BufferAttribute
        fun setXYZW(index: Int, x: Double, y: Double, z: Double, w: Double): BufferAttribute
        fun copyArray(array: dynamic): BufferAttribute
    }

    class Float32BufferAttribute(array: dynamic, itemSize: Int, normalized: Boolean = definedExternally) : BufferAttribute
    class Uint16BufferAttribute(array: dynamic, itemSize: Int, normalized: Boolean = definedExternally) : BufferAttribute
    class Uint32BufferAttribute(array: dynamic, itemSize: Int, normalized: Boolean = definedExternally) : BufferAttribute
    class InstancedBufferAttribute(array: dynamic, itemSize: Int, normalized: Boolean = definedExternally, meshPerAttribute: Int = definedExternally) : BufferAttribute

    class BoxGeometry(width: Number = definedExternally, height: Number = definedExternally, depth: Number = definedExternally, widthSegments: Int = definedExternally, heightSegments: Int = definedExternally, depthSegments: Int = definedExternally) : BufferGeometry
    class SphereGeometry(radius: Number = definedExternally, widthSegments: Int = definedExternally, heightSegments: Int = definedExternally, phiStart: Number = definedExternally, phiLength: Number = definedExternally, thetaStart: Number = definedExternally, thetaLength: Number = definedExternally) : BufferGeometry
    class PlaneGeometry(width: Number = definedExternally, height: Number = definedExternally, widthSegments: Int = definedExternally, heightSegments: Int = definedExternally) : BufferGeometry
    class CircleGeometry(radius: Number = definedExternally, segments: Int = definedExternally, thetaStart: Number = definedExternally, thetaLength: Number = definedExternally) : BufferGeometry
    class RingGeometry(innerRadius: Number = definedExternally, outerRadius: Number = definedExternally, thetaSegments: Int = definedExternally, phiSegments: Int = definedExternally, thetaStart: Number = definedExternally, thetaLength: Number = definedExternally) : BufferGeometry
    class TorusGeometry(radius: Number = definedExternally, tube: Number = definedExternally, radialSegments: Int = definedExternally, tubularSegments: Int = definedExternally, arc: Number = definedExternally) : BufferGeometry
    class CylinderGeometry(radiusTop: Number = definedExternally, radiusBottom: Number = definedExternally, height: Number = definedExternally, radialSegments: Int = definedExternally, heightSegments: Int = definedExternally, openEnded: Boolean = definedExternally) : BufferGeometry
    class TubeGeometry(path: Curve = definedExternally, tubularSegments: Int = definedExternally, radius: Number = definedExternally, radialSegments: Int = definedExternally, closed: Boolean = definedExternally) : BufferGeometry

    open class Curve {
        fun getPoint(t: Double, optionalTarget: Vector3 = definedExternally): Vector3
        fun getPoints(divisions: Int = definedExternally): Array<Vector3>
    }

    class CatmullRomCurve3(points: Array<Vector3> = definedExternally, closed: Boolean = definedExternally, curveType: String = definedExternally, tension: Number = definedExternally) : Curve {
        var points: Array<Vector3>
        var closed: Boolean
    }

    // ---------------------------------------------------------------- materials
    /** Classic materials work under WebGPURenderer (converted to node materials internally). ShaderMaterial / onBeforeCompile do NOT. */
    open class Material : EventDispatcher {
        var id: Int
        var uuid: String
        var name: String
        var opacity: Double
        var transparent: Boolean
        var visible: Boolean
        var side: Int
        var blending: Int
        var alphaTest: Double
        var depthTest: Boolean
        var depthWrite: Boolean
        var colorWrite: Boolean
        var fog: Boolean
        var toneMapped: Boolean
        var premultipliedAlpha: Boolean
        var dithering: Boolean
        var vertexColors: Boolean
        var needsUpdate: Boolean
        var userData: dynamic
        fun clone(): Material
        fun dispose()
    }

    open class LineBasicMaterial(parameters: dynamic = definedExternally) : Material {
        var color: Color
        var linewidth: Double
    }

    class LineDashedMaterial(parameters: dynamic = definedExternally) : LineBasicMaterial {
        var scale: Double
        var dashSize: Double
        var gapSize: Double
    }

    class MeshBasicMaterial(parameters: dynamic = definedExternally) : Material {
        var color: Color
        var map: Texture?
        var wireframe: Boolean
    }

    class MeshStandardMaterial(parameters: dynamic = definedExternally) : Material {
        var color: Color
        var roughness: Double
        var metalness: Double
        var emissive: Color
        var emissiveIntensity: Double
        var map: Texture?
        var wireframe: Boolean
        var flatShading: Boolean
    }

    class PointsMaterial(parameters: dynamic = definedExternally) : Material {
        var color: Color
        var size: Double
        var sizeAttenuation: Boolean
        var map: Texture?
    }

    class SpriteMaterial(parameters: dynamic = definedExternally) : Material {
        var color: Color
        var map: Texture?
        var rotation: Double
        var sizeAttenuation: Boolean
    }

    // node materials (three/webgpu only); assign TSL nodes to colorNode / opacityNode / positionNode ...
    open class NodeMaterial(parameters: dynamic = definedExternally) : Material {
        var colorNode: dynamic
        var opacityNode: dynamic
        var positionNode: dynamic
        var emissiveNode: dynamic
        var fragmentNode: dynamic
        var vertexNode: dynamic
    }

    class MeshBasicNodeMaterial(parameters: dynamic = definedExternally) : NodeMaterial {
        var color: Color
    }

    class LineBasicNodeMaterial(parameters: dynamic = definedExternally) : NodeMaterial {
        var color: Color
    }

    class SpriteNodeMaterial(parameters: dynamic = definedExternally) : NodeMaterial {
        var color: Color
    }

    /** Material for fat lines (the three addons lines webgpu folder). Parameters: color, linewidth, worldUnits, dashed, vertexColors. */
    class Line2NodeMaterial(parameters: dynamic = definedExternally) : NodeMaterial {
        var color: Color
        var linewidth: Double
        var worldUnits: Boolean
        var dashed: Boolean
        var lineColorNode: dynamic
    }

    // ---------------------------------------------------------------- objects
    open class Mesh(geometry: BufferGeometry = definedExternally, material: Material = definedExternally) : Object3D {
        var geometry: BufferGeometry
        var material: Material
    }

    class InstancedMesh(geometry: BufferGeometry, material: Material, count: Int) : Mesh {
        var count: Int
        var instanceMatrix: BufferAttribute
        var instanceColor: BufferAttribute?
        fun setMatrixAt(index: Int, matrix: Matrix4)
        fun setColorAt(index: Int, color: Color)
    }

    /** Open polyline. */
    open class Line(geometry: BufferGeometry = definedExternally, material: Material = definedExternally) : Object3D {
        var geometry: BufferGeometry
        var material: Material
        fun computeLineDistances(): Line
    }

    /** Closed polyline: the natural object for a sampled fiber (first point not repeated). */
    class LineLoop(geometry: BufferGeometry = definedExternally, material: Material = definedExternally) : Line

    /** Independent segments (pairs of vertices). */
    class LineSegments(geometry: BufferGeometry = definedExternally, material: Material = definedExternally) : Line

    class Points(geometry: BufferGeometry = definedExternally, material: Material = definedExternally) : Object3D {
        var geometry: BufferGeometry
        var material: Material
    }

    class Sprite(material: Material = definedExternally) : Object3D {
        var material: Material
        var center: Vector2
    }

    // ---------------------------------------------------------------- lights
    open class Light(color: dynamic = definedExternally, intensity: Number = definedExternally) : Object3D {
        var color: Color
        var intensity: Double
    }

    class AmbientLight(color: dynamic = definedExternally, intensity: Number = definedExternally) : Light
    class HemisphereLight(skyColor: dynamic = definedExternally, groundColor: dynamic = definedExternally, intensity: Number = definedExternally) : Light
    class PointLight(color: dynamic = definedExternally, intensity: Number = definedExternally, distance: Number = definedExternally, decay: Number = definedExternally) : Light
    class DirectionalLight(color: dynamic = definedExternally, intensity: Number = definedExternally) : Light {
        var target: Object3D
    }

    // ---------------------------------------------------------------- textures
    open class Texture(image: dynamic = definedExternally) : EventDispatcher {
        var image: dynamic
        var needsUpdate: Boolean
        var colorSpace: String
        var magFilter: Int
        var minFilter: Int
        var wrapS: Int
        var wrapT: Int
        fun dispose()
    }

    class CanvasTexture(canvas: HTMLCanvasElement) : Texture
    class DataTexture(data: dynamic = definedExternally, width: Int = definedExternally, height: Int = definedExternally, format: Int = definedExternally, type: Int = definedExternally) : Texture

    // ---------------------------------------------------------------- constants
    val FrontSide: Int
    val BackSide: Int
    val DoubleSide: Int
    val NoBlending: Int
    val NormalBlending: Int
    val AdditiveBlending: Int
    val SubtractiveBlending: Int
    val MultiplyBlending: Int
    val StaticDrawUsage: Int
    val DynamicDrawUsage: Int
    val StreamDrawUsage: Int
    val NoToneMapping: Int
    val LinearToneMapping: Int
    val ReinhardToneMapping: Int
    val ACESFilmicToneMapping: Int
    val AgXToneMapping: Int
    val NeutralToneMapping: Int
    val SRGBColorSpace: String
    val LinearSRGBColorSpace: String
    val NearestFilter: Int
    val LinearFilter: Int
    val LinearMipmapLinearFilter: Int
    val RepeatWrapping: Int
    val ClampToEdgeWrapping: Int
    val RGBAFormat: Int
    val FloatType: Int
    val HalfFloatType: Int
    val UnsignedByteType: Int
    val PCFSoftShadowMap: Int
}

/** Wrap a Kotlin FloatArray (a Float32Array at runtime, zero copy) for BufferAttribute constructors. */
@Suppress("UnsafeCastFromDynamic")
fun FloatArray.asFloat32Array(): Float32Array = this.asDynamic()

/** Build a plain JS object: `jsObject { antialias = true; forceWebGL = true }`. */
fun jsObject(init: dynamic.() -> Unit): dynamic {
    val o = js("({})")
    init(o)
    return o
}
