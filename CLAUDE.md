# Spacegraph Project Instructions

## Graph Database Architecture

Spacegraph implements graph database capabilities with TrikeShed data structures.

## Graph Data Structures

- Use `Series<T>` for vertex and edge collections
- Use `Join<A,B>` for adjacency mappings and graph relationships
- Prefer TrikeShed patterns for graph traversal data
- Implement graph algorithms with Series-based approaches

## Performance Patterns

- for loops are preferred for graph traversal algorithms
- Use primitive arrays for high-performance edge lists
- Avoid List<T> in graph processing hot paths
- Leverage cache-friendly TrikeShed data layout

## Graph Operations

- **Vertex Management**: Series<Vertex> for vertex collections
- **Edge Mapping**: Join<VertexId, Series<Edge>> for adjacency
- **Path Finding**: Series<VertexId> for path representations
- **Graph Queries**: Join-based indexing for efficient lookups

## Integration Guidelines

- Connect with other SuperBikeShed projects for data sources
- Use museum preservation for graph algorithm implementations
- Follow global TrikeShed type system patterns
- Maintain deterministic graph operations where possible