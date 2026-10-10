package nx.patcher

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

/**
 * Describes a method by the traits that survive obfuscation, so it can be found again in
 * other app versions even though class and method names change.
 *
 * - [strings]: string constants the method must all reference. They are indexed, so they are
 *   the fast way to narrow down candidates; without them every method is checked.
 * - [literals]: numeric constants (feature flag ids, resource ids) the method must all load.
 * - [opcodes]: an instruction sequence that must appear back to back (`null` matches any).
 * - [parameters]: exact types, or `"L"` for any object type (obfuscated classes).
 */
class MethodFilter(
    val label: String,
    val strings: List<String> = emptyList(),
    val returnType: String? = null,
    val parameters: List<String>? = null,
    val isStatic: Boolean? = null,
    val name: String? = null,
    val literals: List<Long> = emptyList(),
    val opcodes: List<Opcode?>? = null,
    val extra: ((Method) -> Boolean)? = null,
) {
    init {
        require(strings.isNotEmpty() || literals.isNotEmpty() || opcodes != null) {
            "$label: needs strings, literals or opcodes"
        }
    }

    fun matches(method: Method): Boolean {
        if (name != null && method.name != name) return false
        if (returnType != null && !typeMatches(returnType, method.returnType)) return false
        if (isStatic != null && AccessFlags.STATIC.isSet(method.accessFlags) != isStatic) return false
        if (parameters != null) {
            val actual = method.parameterTypes.map { it.toString() }
            if (actual.size != parameters.size) return false
            if (!parameters.zip(actual).all { (want, have) -> typeMatches(want, have) }) return false
        }
        if (literals.isNotEmpty() || opcodes != null) {
            val instructions = method.implementation?.instructions?.toList() ?: return false
            if (literals.isNotEmpty()) {
                val loaded = instructions.mapNotNullTo(HashSet()) { (it as? WideLiteralInstruction)?.wideLiteral }
                if (!loaded.containsAll(literals)) return false
            }
            if (opcodes != null && !containsSequence(instructions.map { it.opcode }, opcodes)) return false
        }
        return extra?.invoke(method) ?: true
    }

    private fun containsSequence(actual: List<Opcode>, wanted: List<Opcode?>): Boolean =
        (0..actual.size - wanted.size).any { start ->
            wanted.indices.all { i -> wanted[i] == null || wanted[i] == actual[start + i] }
        }

    private fun typeMatches(want: String, have: String) =
        if (want == "L") have.startsWith("L") else want == have
}

fun DexStore.find(filter: MethodFilter): List<MethodHit> {
    if (filter.strings.isEmpty()) {
        return methods().filter { (_, m) -> filter.matches(m) }.map { (c, m) -> MethodHit(c.type, m) }.toList()
    }
    indexStrings(filter.strings.toSet())
    val candidates = filter.strings
        .map { methodsUsing(it).toSet() }
        .reduce { a, b -> a intersect b }
    return candidates.filter { filter.matches(it.method) }
}

/** Finds exactly one method, failing the patch otherwise. */
fun DexStore.single(filter: MethodFilter): MethodHit {
    val hits = find(filter)
    return when (hits.size) {
        1 -> hits.single()
        0 -> throw PatchException("${filter.label}: method not found (unsupported app version?)")
        else -> throw PatchException("${filter.label}: ${hits.size} methods matched, expected 1")
    }
}
