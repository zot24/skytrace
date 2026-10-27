package com.zot24.skytrace

import org.maplibre.android.style.expressions.Expression

enum class TypeChip(val label: String, val kinds: List<Silhouette>) {
    AIRLINERS("Airliners", listOf(Silhouette.WIDE_BODY, Silhouette.NARROW_BODY, Silhouette.REGIONAL_JET)),
    BIZJETS("Bizjets", listOf(Silhouette.BIZJET)),
    PROPS("Props/light", listOf(Silhouette.TURBOPROP, Silhouette.LIGHT_PISTON)),
    HELIS("Helicopters", listOf(Silhouette.HELI_LIGHT, Silhouette.HELI_MEDIUM, Silhouette.HELI_HEAVY)),
    FAVOURITES("Favourites", emptyList()),
}

/** No chip selected means every type. Several chips widen the set. */
fun keepsPlane(chips: Set<TypeChip>, kind: Silhouette, favourite: Boolean): Boolean {
    if (chips.isEmpty()) return true
    for (chip in chips) {
        if (chip == TypeChip.FAVOURITES) {
            if (favourite) return true
        } else if (kind in chip.kinds) {
            return true
        }
    }
    return false
}

/** The same rule as [keepsPlane] as a layer filter, so a chip change shows before the next source push. Pinned planes always pass. */
fun planeFilterExpression(chips: Set<TypeChip>): Expression? {
    if (chips.isEmpty()) return null
    val parts = ArrayList<Expression>(3)
    parts += Expression.eq(Expression.get("pin"), true)
    val kinds = chips.flatMap { it.kinds }
    if (kinds.isNotEmpty()) {
        val stops = kinds.map { Expression.stop(it.name, true) }.toTypedArray()
        parts += Expression.match(Expression.get("type"), Expression.literal(false), *stops)
    }
    if (TypeChip.FAVOURITES in chips) parts += Expression.eq(Expression.get("fav"), true)
    return Expression.any(*parts.toTypedArray())
}
