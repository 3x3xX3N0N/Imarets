# Blackboard Viewer Specification (Spacegraph)

## Overview
The Blackboard Viewer is an immersive, extensible 3D/2D visualization tool for exploring fiduciary attention, memvid events, and divine archive indexes. It leverages Spacegraph's hybrid rendering, fractal navigation, and dynamic node/edge metaphors to unify and associate complex information.

---

## 1. Data Model
- **Node Types:**
  - AttentionEvent (DocumentFocus, CorpusScan, ConceptExtraction, FiduciaryAction)
  - DivineIndexEntry (archive file, offset, size, type, etc)
  - Meta/Group nodes (for clustering, summary, or navigation)
- **Edge Types:**
  - Attention flow (temporal or causal links)
  - Data association (e.g., document to concept, archive to entry)

### Acceptance Criteria
- [ ] Can ingest and parse MemvidAttentionBridge events as nodes/edges
- [ ] Can ingest and parse divine index JSON as nodes/edges
- [ ] Can associate nodes by type, time, or semantic link

---

## 2. Visual Metaphors
- **Red LED:** Node with glowing/animated red sphere for urgent/high-attention items
- **Spreadsheet:** Node with embedded HTML table for tabular data
- **Flag/Ziggurat:** Node with custom 3D geometry for status/priority
- **Hilbert Heatmap:** Node with canvas/SVG heatmap for dense data
- **Storage Layout:** Node with bar/box visualization for capacity/meta/full/empty

### Acceptance Criteria
- [ ] Can render a node as a red LED based on attention/intensity
- [ ] Can render a node as a spreadsheet (HTML table) for index/tabular data
- [ ] Can render a node as a flag/ziggurat for status/priority
- [ ] Can render a node as a Hilbert heatmap for dense/temporal data
- [ ] Can render a node as a storage layout for meta/full/empty states

---

## 3. Navigation & Interaction
- **3D/2D/Fractal Navigation:** Pan, zoom, orbit, and semantic zoom
- **Selection & Focus:** Click/select nodes, focus camera, show details
- **Grouping & Association:** Drag to group, link, or unify nodes
- **Context Menus:** Right-click for node/edge actions
- **Dynamic Data Loading:** Load new data on demand

### Acceptance Criteria
- [ ] Can pan, zoom, and orbit in 3D/2D
- [ ] Can zoom in/out to reveal more/less detail (fractal/semantic zoom)
- [ ] Can select/focus nodes and display details
- [ ] Can group/associate nodes visually and semantically
- [ ] Can load and update data dynamically

---

## 4. Integration
- **MemvidAttentionBridge:** Accepts event streams for live/recorded attention
- **Divine Indexes:** Loads and visualizes archive index files
- **Extensibility:** New node/edge types and metaphors can be added easily

### Acceptance Criteria
- [ ] Can load and visualize MemvidAttentionBridge test data
- [ ] Can load and visualize divine index files
- [ ] Can add new node/edge types and visual metaphors with minimal code

---

## 5. Example TDD Scenarios
- [ ] Given a set of DocumentFocus and CorpusScan events, the viewer displays them as nodes with correct metaphors and links
- [ ] Given a divine index JSON, the viewer displays archive entries as spreadsheet or storage nodes
- [ ] When a node's attention score exceeds a threshold, it appears as a red LED
- [ ] When zooming in, node details and associations are revealed fractally
- [ ] When grouping nodes, a meta/group node is created and visualized

---

## 6. Implementation Notes
- Use Spacegraph's `loadDynamicData` to inject graph data
- Map node/edge types to visual metaphors via node properties
- Use CSS3D for HTML-rich nodes, WebGL for 3D shapes
- Provide demo/test data for each scenario

---

## 7. References
- [SpacegraphKT README](../src/js/README.md)
- [MemvidAttentionBridgeTest.kt](../../fiduciary/src/commonTest/kotlin/fiduciary/attention/MemvidAttentionBridgeTest.kt)
- [Divine Index Fetcher](../../fiduciary/fetch-divine-indexes.kts)

---

## 8. Integration of BoingDemo Innovations

BoingDemo provides multiplatform graphics, animation, and audio patterns that will be curated and integrated into Spacegraph to advance the Blackboard Viewer:

### 8.1. Canvas & Animation Abstraction
- Adopt BoingDemo's `EconoCanvas` expect/actual pattern for portable, high-performance 2D drawing and animation.
- Use this abstraction for:
  - Custom node/edge rendering (e.g., animated overlays, 2D/3D hybrid visuals)
  - Animated metaphors (bouncing, pulsing, glowing nodes)
  - HUDs and insets within the 3D scene

### 8.2. Physics-based Animation
- Integrate BoingDemo's animation loop and event-driven feedback for:
  - Smooth, platform-consistent node/edge animations
  - Attention pulses, activity spikes, or interactive metaphors

### 8.3. Audio Feedback
- Use BoingDemo's multiplatform audio feedback for immersive, event-driven sound cues:
  - Attention spikes, node selection, alerts, or other interactive events

### 8.4. TrikeShed Data Structures
- Leverage `Series<T>` and `Join<A,B>` for:
  - Blackboard Viewer state management
  - Animation sequences
  - Configuration and association mappings

### 8.5. Clean Architecture & Extensibility
- Maintain clean separation of concerns and platform-agnostic core logic
- Ensure all new features are test-driven, with demo/test cases for each integration

### Acceptance Criteria
- [ ] Blackboard Viewer can use BoingDemo's canvas abstraction for custom rendering
- [ ] Animated node/edge behaviors (e.g., bouncing, pulsing) are available and testable
- [ ] Audio feedback is integrated and works across supported platforms
- [ ] TrikeShed data structures are used for state and animation management
- [ ] All integrations are covered by TDD scenarios and demo/test data 