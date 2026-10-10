package nx.patcher

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.ByteArrayInputStream

/** A method found in the app, identified by its class and signature. */
data class MethodHit(val classType: String, val method: Method)

/**
 * Holds all dex files of an APK. Classes are read lazily from the original files and only
 * the dex files that contain modified classes are written again.
 */
class DexStore(entries: List<Pair<String, ByteArray>>) {
    class DexEntry(val name: String, val file: DexBackedDexFile) {
        val classes: MutableList<ClassDef> = file.classes.toMutableList()
        var modified = false
    }

    val dexes: List<DexEntry> = entries.map { (name, bytes) ->
        DexEntry(name, DexBackedDexFile.fromInputStream(null, ByteArrayInputStream(bytes)))
    }

    private val classLocation = HashMap<String, Pair<DexEntry, Int>>().apply {
        for (dex in dexes) dex.classes.forEachIndexed { i, c -> put(c.type, dex to i) }
    }

    private val stringIndex = HashMap<String, MutableList<MethodHit>>()

    fun classDef(type: String): ClassDef? = classLocation[type]?.let { (dex, i) -> dex.classes[i] }

    /** Returns an editable version of [type], replacing the original class. */
    fun edit(type: String): EditableClass {
        val (dex, i) = classLocation[type] ?: throw PatchException("Class not found: $type")
        val current = dex.classes[i]
        if (current is EditableClass) return current
        return EditableClass(current).also {
            dex.classes[i] = it
            dex.modified = true
        }
    }

    fun edit(hit: MethodHit): EditableMethod = edit(hit.classType).methodLike(hit.method)

    /** Every method of the app, in its current (possibly modified) state. */
    fun methods(): Sequence<Pair<ClassDef, Method>> = dexes.asSequence()
        .flatMap { it.classes.asSequence() }
        .flatMap { c -> c.methods.asSequence().map { c to it } }

    /** Indexes the methods referencing any of [strings], so [methodsUsing] is fast. */
    fun indexStrings(strings: Set<String>) {
        val missing = strings - stringIndex.keys
        if (missing.isEmpty()) return
        missing.forEach { stringIndex[it] = mutableListOf() }
        for ((c, m) in methods()) {
            val impl = m.implementation ?: continue
            var seen: MutableSet<String>? = null
            for (ins in impl.instructions) {
                val s = ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ?: continue
                if (s !in missing) continue
                if (seen == null) seen = HashSet()
                if (seen.add(s)) stringIndex.getValue(s) += MethodHit(c.type, m)
            }
        }
    }

    fun methodsUsing(string: String): List<MethodHit> {
        indexStrings(setOf(string))
        return stringIndex.getValue(string)
    }

    /**
     * Rewrites every `const-string` whose value [transform] maps to a new value.
     * Returns how many instructions were changed.
     */
    fun replaceStrings(transform: (String) -> String?): Int {
        // Collect first, then edit, so editing does not disturb the iteration.
        val targets = ArrayList<Pair<MethodHit, List<Pair<Int, String>>>>()
        for ((c, m) in methods()) {
            val impl = m.implementation ?: continue
            var changes: MutableList<Pair<Int, String>>? = null
            impl.instructions.forEachIndexed { index, ins ->
                val s = ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string ?: return@forEachIndexed
                val replacement = transform(s) ?: return@forEachIndexed
                if (replacement == s) return@forEachIndexed
                if (changes == null) changes = mutableListOf()
                changes!! += index to replacement
            }
            changes?.let { targets += MethodHit(c.type, m) to it }
        }
        var count = 0
        for ((hit, changes) in targets) {
            val code = edit(hit).code
            for ((index, value) in changes) {
                code.replaceConstString(index, value)
                count++
            }
        }
        return count
    }

    /**
     * Dex files for classes moved out of a dex that hooks pushed over the 65536 reference limit.
     * Filled by [writeModified]; the caller stores them as additional `classesN.dex` entries.
     */
    val overflow = ArrayList<ByteArray>()

    /** Serializes the dex files that changed, keyed by entry name. */
    fun writeModified(onWrite: (String) -> Unit = {}): Map<String, ByteArray> {
        overflow.clear()
        val moved = ArrayList<ClassDef>()
        val result = dexes.filter { it.modified }.associate { dex ->
            onWrite(dex.name)
            val bytes = try {
                write(dex.file.opcodes, dex.classes)
            } catch (e: Exception) {
                if (!isReferenceOverflow(e)) throw e
                // Hooks added references; move the edited classes to a dex of their own.
                val edited = dex.classes.filter { it is EditableClass }
                onWrite("${dex.name}: ${edited.size} değişen sınıf ayrı dex'e taşındı (65536 sınırı)")
                moved += edited
                write(dex.file.opcodes, dex.classes.filter { it !is EditableClass })
            }
            dex.name to bytes
        }
        if (moved.isNotEmpty()) overflow += write(dexes.first().file.opcodes, moved)
        return result
    }

    private fun write(opcodes: com.android.tools.smali.dexlib2.Opcodes, classes: List<ClassDef>): ByteArray {
        val pool = DexPool(opcodes)
        classes.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data.copyOf(store.size)
    }

    private fun isReferenceOverflow(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val m = cur.message ?: ""
            if ("out of range" in m || "too many" in m.lowercase() || "index is too large" in m.lowercase()) return true
            cur = cur.cause
        }
        return false
    }
}
