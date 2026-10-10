package nx.cli

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipFile

/** Developer tooling for studying an APK's bytecode while writing patches. */
object Analyze {
    private fun classes(apk: File): Sequence<ClassDef> = sequence {
        ZipFile(apk).use { zip ->
            val dexNames = zip.entries().asSequence().map { it.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }.sorted().toList()
            for (name in dexNames) {
                val bytes = zip.getInputStream(zip.getEntry(name)).readBytes()
                yieldAll(DexBackedDexFile(Opcodes.getDefault(), bytes).classes)
            }
        }
    }

    /** Prints every method that references a string matching [pattern]. */
    fun grep(apk: File, pattern: String) {
        val regex = Regex(pattern)
        for (c in classes(apk)) for (m in c.methods) {
            val impl = m.implementation ?: continue
            val hits = impl.instructions.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.filter { regex.containsMatchIn(it) }.toList()
            if (hits.isNotEmpty()) println("${c.type}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}  ${hits.distinct().take(6)}")
        }
    }

    /** Prints every method that loads the numeric constant [value]. */
    fun literal(apk: File, value: Long) {
        for (c in classes(apk)) for (m in c.methods) {
            val impl = m.implementation ?: continue
            if (impl.instructions.any { (it as? com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction)?.wideLiteral == value }) {
                println("${c.type}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}")
            }
        }
    }

    /** Disassembles a whole class to smali. */
    fun dump(apk: File, type: String) {
        val c = classes(apk).firstOrNull { it.type == type } ?: error("class not found: $type")
        val sw = StringWriter()
        BaksmaliWriter(sw, null).use { ClassDefinition(BaksmaliOptions(), c).writeTo(it) }
        println(sw)
    }
}
