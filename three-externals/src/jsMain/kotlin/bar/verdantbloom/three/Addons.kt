@file:Suppress("unused")

package bar.verdantbloom.three

import org.w3c.dom.HTMLElement

/*
 * three.js addons. These files `import ... from 'three'` internally; :site's webpack alias
 * (site/webpack.config.d/three-single-copy.js) points that at three.webgpu.js so there is ONE three.
 */

/** DOM element positioned in 3D by CSS3DRenderer. 1 CSS px = 1 world unit before object scale. */
@JsModule("three/addons/renderers/CSS3DRenderer.js")
external object CSS3D {
    open class CSS3DObject(element: HTMLElement = definedExternally) : THREE.Object3D {
        val isCSS3DObject: Boolean
        var element: HTMLElement
    }

    /** Always faces the camera. */
    class CSS3DSprite(element: HTMLElement = definedExternally) : CSS3DObject {
        var rotation2D: Double
    }

    class CSS3DRenderer(parameters: dynamic = definedExternally) {
        val domElement: HTMLElement
        fun getSize(): dynamic
        fun setSize(width: Number, height: Number)
        fun render(scene: THREE.Object3D, camera: THREE.Camera)
    }
}

/** Fat lines for WebGPURenderer (works on both backends). Material: THREE.Line2NodeMaterial. */
@JsModule("three/addons/lines/webgpu/Line2.js")
external object FatLine2 {
    class Line2(geometry: LineGeometryModule.LineGeometry = definedExternally, material: THREE.Material = definedExternally) : THREE.Mesh {
        fun computeLineDistances(): Line2
    }
}

@JsModule("three/addons/lines/LineGeometry.js")
external object LineGeometryModule {
    class LineGeometry : THREE.BufferGeometry {
        /** Flat xyz array (Array<Number> or Float32Array). For a closed loop repeat the first point at the end. */
        fun setPositions(array: dynamic): LineGeometry
        fun setColors(array: dynamic): LineGeometry
    }
}
