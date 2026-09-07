# spacegraph Documentation

## Overview
This directory contains consolidated documentation for the spacegraph project.
Consolidated on: Tue Jun 24 20:49:40 EDT 2025

## Files
- [testing_and_refinement_report](testing_and_refinement_report.md) (   13853 bytes,      132 lines)
- [conceptual_ui_outline](conceptual_ui_outline.md) (    7130 bytes,       72 lines)
- [spacegraph_kt_design](spacegraph_kt_design.md) (   27903 bytes,      604 lines)
- [k2script_integration_design](k2script_integration_design.md) (   17587 bytes,      378 lines)
- [fixes_verification_report](fixes_verification_report.md) (    7725 bytes,       73 lines)

## API: nodeConfig and edgeConfig Structure

- For direct agent calls (e.g., `addNode`), use a flat object: `{ type: "note", label: "My Note", content: "..." }`.
- For graph loading (e.g., `loadGraphData`), use a nested structure: `{ id: "node1", type: "note", position: {...}, data: { label: "My Note", ... } }`.
- The API will handle both, but direct calls should avoid unnecessary nesting.
