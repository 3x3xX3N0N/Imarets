package com.example.spacegraphkt.main

import com.example.spacegraphkt.api.AgentAPI
import com.example.spacegraphkt.core.NoteNode
import com.example.spacegraphkt.core.SpaceGraph
import com.example.spacegraphkt.data.NodeData
import com.example.spacegraphkt.data.Vector3D
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * The engine's demo graph (four NoteNodes, four edges), formerly `fun main()` of the POC.
 * :engine is a library now, so the executable (:site) calls this explicitly.
 *
 * @param container positioned element that will host the GPU canvas + CSS3D layer
 * @param forceWebGL true = webgl rung, false = try WebGPU (three falls back to WebGL2 by itself)
 */
fun runEngineDemo(container: HTMLElement, forceWebGL: Boolean = true): SpaceGraph {
    val spaceGraph = SpaceGraph(container, null, forceWebGL = forceWebGL)

    val agentApi = AgentAPI(spaceGraph)
    window.asDynamic().spaceGraphAgent = agentApi
    spaceGraph.agentApi = agentApi

    val node1 = NoteNode(
        "n1", Vector3D(-150.0, 50.0, 0.0),
        NodeData(
            id = "n1", label = "Hello", type = "note",
            content = "Welcome to SpaceGraphKT!\nThis is a NoteNode.",
            width = 200.0, height = 100.0,
        ),
    )
    node1.setBackgroundColor("rgba(100,100,200,0.9)")

    val node2 = NoteNode(
        "n2", Vector3D(150.0, -50.0, 0.0),
        NodeData(
            id = "n2", label = "World", type = "note",
            content = "You can drag nodes, pan (left-click drag bg), and zoom (wheel).\nRight-click for context menus.",
            width = 220.0, height = 120.0,
        ),
    )

    val node3 = NoteNode(
        "n3", Vector3D(0.0, 150.0, -50.0),
        NodeData(
            id = "n3", label = "Features", type = "note",
            content = """
                - Create nodes (right-click bg)
                - Link nodes (right-click node -> Start Link)
                - Edit content (for NoteNodes)
                - Delete nodes/edges
                - Basic styling & customization
            """.trimIndent(),
            width = 250.0, height = 150.0,
        ),
    )
    node3.setBackgroundColor("rgba(100,200,100,0.9)")

    val node4 = NoteNode(
        "n4", Vector3D(-200.0, -150.0, 50.0),
        NodeData(
            id = "n4", label = "Editable", type = "note",
            content = "This node is editable. Try typing here!",
            editable = true,
            width = 180.0, height = 80.0,
        ),
    )

    spaceGraph.addNode(node1)
    spaceGraph.addNode(node2)
    spaceGraph.addNode(node3)
    spaceGraph.addNode(node4)

    spaceGraph.addEdge(node1, node2, label = "connects to")
    spaceGraph.addEdge(node1, node3, label = "explains")
    spaceGraph.addEdge(node2, node4, label = "related to")
    spaceGraph.addEdge(node3, node4, label = "another link")

    spaceGraph.layoutEngine.kick(1.5)
    spaceGraph.layoutEngine.runOnce(150)

    window.setTimeout({ spaceGraph.centerView(null, 0.8) }, 200)

    window.asDynamic().space = spaceGraph // for debugging from the console
    console.log("SpaceGraph Kotlin/JS engine demo initialised. Agent API at window.spaceGraphAgent")
    spaceGraph.uiManager.showStatus("SpaceGraphKT engine demo. Agent API ready.", 5000)
    return spaceGraph
}
