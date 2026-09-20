package com.example.spacegraphkt.harness

import bar.verdantbloom.three.THREE
import bar.verdantbloom.three.jsObject
import com.example.spacegraphkt.core.NoteNode
import com.example.spacegraphkt.core.PickPhase
import com.example.spacegraphkt.core.SpaceGraph
import com.example.spacegraphkt.data.NodeData
import com.example.spacegraphkt.data.SpaceGraphOptions
import com.example.spacegraphkt.data.Vector3D
import com.example.spacegraphkt.zui.Route
import com.example.spacegraphkt.zui.Tour
import com.example.spacegraphkt.zui.TourStop
import com.example.spacegraphkt.zui.ZuiNavigator
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams

/**
 * Browser harness for the engine ALONE (only compiled with -PengineHarness; never part of :site).
 * Query: `mode=readonly|edit` (default readonly), `renderer=webgl|webgpu` (default webgl).
 * Everything interesting hangs off `window.sgHarness` so it can be driven from the console.
 */
fun main() {
    val params = URLSearchParams(window.location.search)
    val readOnly = params.get("mode") != "edit"
    val forceWebGL = params.get("renderer") != "webgpu"
    val host = document.getElementById("space") as? HTMLElement ?: return
    val status = document.getElementById("status")

    val graph = SpaceGraph(host, null, SpaceGraphOptions(readOnly = readOnly, layoutEnabled = !readOnly), forceWebGL = forceWebGL)

    val spots = listOf(-420.0 to 160.0, 0.0 to 220.0, 420.0 to 160.0, -220.0 to -200.0)
    val ids = listOf("mini", "write", "dark", "flagship")
    ids.forEachIndexed { i, id ->
        graph.addNode(NoteNode(id, Vector3D(spots[i].first, spots[i].second, 0.0),
            NodeData(id = id, label = id, type = "note", content = "W0${i + 1} ${id.uppercase()}\nbuilt node <b>text only</b>", width = 220.0, height = 110.0)))
    }
    graph.addEdge(graph.getNodeById("mini"), graph.getNodeById("write"))
    graph.addEdge(graph.getNodeById("write"), graph.getNodeById("dark"))

    graph.addNode(com.example.spacegraphkt.core.ShapeNode("box", Vector3D(-40.0, -330.0, 0.0),
        NodeData(id = "box", label = "box", type = "shape", shapeType = "box", shapeSize = 60.0, shapeColor = 0xffd27a), "box", 60.0, 0xffd27a))

    // an element that already lives in the document gets lifted into the graph
    val card = document.getElementById("doc-card") as HTMLElement
    val adopted = graph.adoptElement(card, Vector3D(260.0, -200.0, 0.0))

    // something "foreign" in the shared scene + a frame listener + a pick hook, the way bloom-three would
    val ring = THREE.Mesh(THREE.TorusGeometry(90, 6, 12, 64), THREE.MeshBasicMaterial(jsObject { color = 0x39ffb0 }))
    ring.position.set(0.0, -20.0, -100.0)
    graph.scene.add(ring)
    var frames = 0
    graph.addFrameListener { dt, _ -> frames++; ring.rotation.z += dt * 0.5 }
    val picks = js("[]")
    graph.addPickHook { e ->
        if (e.phase == PickPhase.TAP || e.phase == PickPhase.DOWN) picks.push("${e.phase.name}:${e.x.toInt()},${e.y.toInt()}:${e.nodeUnderPointer?.id}")
        // claim taps on empty space near the centre of the container ("the ring")
        e.phase == PickPhase.TAP && e.nodeUnderPointer == null && window.asDynamic().sgClaimTaps == true
    }

    val tour = Tour(ids.map { TourStop(it, it.uppercase(), Route.Pour(it)) } + TourStop(adopted.id, "TAB", Route.Tab))
    val navigator = ZuiNavigator(graph, tour)
    (document.getElementById("tour-prev"))?.addEventListener("click", { tour.prev() })
    (document.getElementById("tour-next"))?.addEventListener("click", { tour.next() })
    val label = document.getElementById("tour-label")
    tour.addChangeListener { stop, _ -> label?.textContent = (stop?.title ?: "OVERVIEW") + "  " + tour.positionLabel }

    graph.ready.then { result ->
        status?.textContent = "mode=${if (readOnly) "readonly" else "edit"} $result"
        navigator.start()
    }

    window.asDynamic().sgHarness = jsObject {
        this.graph = graph
        this.tour = tour
        this.navigator = navigator
        this.picks = picks
        this.frames = { frames }
        this.flyTo = { id: String -> graph.flyTo(id) }
        this.back = { graph.back() }
        this.reset = { graph.reset() }
        this.lods = { graph.nodes.values.joinToString(",") { "${it.id}=${(it as? com.example.spacegraphkt.core.HtmlNodeElement)?.lod?.attr}" } }
        this.target = { graph.cameraController.currentTargetNodeId }
        this.history = { graph.cameraController.viewHistory.size }
        this.camZ = { graph.camera.position.z }
        this.cam = { "${graph.camera.position.x.toInt()},${graph.camera.position.y.toInt()},${graph.camera.position.z.toInt()}" }
        this.dispose = { navigator.dispose(); graph.dispose() }
    }
}
