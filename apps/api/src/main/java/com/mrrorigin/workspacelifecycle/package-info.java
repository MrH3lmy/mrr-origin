/**
 * Cross-module workspace lifecycle orchestration (#62): owner-only workspace deletion, the only
 * concern in this codebase that legitimately needs to drive every other module's own deletion service
 * in one durable, resumable run. Per ADR-0008, this is the one module allowed to depend on every
 * other module below it -- it calls each module's own exposed application service (never reaches into
 * another module's repository or persistence internals directly), so the module-ownership boundary
 * ARCHITECTURE.md's module table describes for ordinary feature code is preserved even though this
 * module's job is inherently cross-cutting.
 */
package com.mrrorigin.workspacelifecycle;
