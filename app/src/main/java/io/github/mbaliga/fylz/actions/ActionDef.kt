package io.github.mbaliga.fylz.actions

/**
 * The data half of an action -- mirrored field for field in `core/crates/fylz-actions`'s Rust
 * `ActionDef` (design §2.2/§3). The Kotlin-only half (predicates, dynamic labels, the actual
 * handler) is [BuiltInBinding].
 */
data class ActionDef(
    val id: ActionId,
    val titleKey: String,
    val icon: IconRef,
    val whenExpr: String? = null,
    val placements: List<Placement>,
    val confirm: ConfirmPolicy,
    val body: ActionBody,
    val origin: Origin,
    val destructive: Boolean = false,
    val requiresTarget: TargetKind? = null,
)
