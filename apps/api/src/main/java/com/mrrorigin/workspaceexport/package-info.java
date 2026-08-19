/**
 * Cross-module workspace data export orchestration (#64): manager-only, synchronously streamed ZIP
 * export of everything MRROrigin holds for a workspace. Per ADR-0009, this is the second module (after
 * {@code workspacelifecycle}, ADR-0008) allowed to depend on multiple other modules below it --
 * {@code workspace}, {@code billing}, {@code revenue}, {@code attribution}, {@code reporting}, {@code
 * notification}, {@code tracking} -- because manager-only workspace export, like owner-only workspace
 * deletion, is a concern whose job is inherently cross-cutting. It never touches another module's
 * tables directly: it only calls each domain module's own exposed {@code *WorkspaceExportService}.
 */
package com.mrrorigin.workspaceexport;
