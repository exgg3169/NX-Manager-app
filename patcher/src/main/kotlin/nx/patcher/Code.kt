package nx.patcher

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

/** Number of registers taken by the method's parameters, including `this`. */
val Method.parameterRegisterCount: Int
    get() = (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
        parameterTypes.map { it.toString() }.map { if (it == "J" || it == "D") 2 else 1 }.sum()

/**
 * Replaces the whole body so the method immediately returns [value]
 * (ignored for `void` methods; `null` returns null for object types).
 */
fun EditableMethod.returnEarly(value: Any? = null) {
    val params = parameterRegisterCount
    val ret = returnType
    val instructions = when {
        ret == "V" -> listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
        ret.length == 1 && ret in "ZBSCI" -> {
            val v = when (value) {
                is Boolean -> if (value) 1 else 0
                is Int -> value
                null -> 0
                else -> throw PatchException("Cannot return $value from $this")
            }
            val load = if (v in -8..7) ImmutableInstruction11n(Opcode.CONST_4, 0, v)
            else ImmutableInstruction31i(Opcode.CONST, 0, v)
            listOf(load, ImmutableInstruction11x(Opcode.RETURN, 0))
        }
        ret.startsWith("L") || ret.startsWith("[") -> {
            val load = when (value) {
                null -> ImmutableInstruction11n(Opcode.CONST_4, 0, 0)
                is String -> ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(value))
                else -> throw PatchException("Cannot return $value from $this")
            }
            listOf(load, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
        }
        else -> throw PatchException("Unsupported return type $ret in $this")
    }
    val locals = if (ret == "V") 0 else 1
    setCode(ImmutableMethodImplementation(params + locals, instructions, null, null))
}

fun MutableMethodImplementation.replaceConstString(index: Int, value: String) {
    val register = (instructions[index] as OneRegisterInstruction).registerA
    replaceInstruction(index, BuilderInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value)))
}

/** Replaces the instruction at [index] with `const/4 register, value` (used to force a result). */
fun MutableMethodImplementation.replaceWithConst4(index: Int, value: Int) {
    val register = (instructions[index] as OneRegisterInstruction).registerA
    replaceInstruction(index, BuilderInstruction11n(Opcode.CONST_4, register, value))
}

/**
 * Inserts [newInstructions] before the instruction at [index]. Branches that targeted the old
 * instruction keep targeting it, so insertion points must not be branch targets.
 */
fun MutableMethodImplementation.insertAt(index: Int, newInstructions: List<BuilderInstruction>) {
    if (index < instructions.size && instructions[index].location.labels.isNotEmpty()) {
        throw PatchException("Refusing to insert at a branch target (index $index)")
    }
    newInstructions.asReversed().forEach { addInstruction(index, it) }
}

/** The register number of parameter `p<n>` (`p0` is `this` for instance methods). */
fun MutableMethodImplementation.parameterRegister(method: Method, n: Int): Int =
    registerCount - method.parameterRegisterCount + n

fun invokeStaticRange(register: Int, method: String): BuilderInstruction3rc {
    val ref = parseMethodReference(method)
    return BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, ref.parameterTypes.size, ref)
}

/** Parses `Lcls;->name(Lparams;)Lret;`. */
fun parseMethodReference(smali: String): MethodReference {
    val cls = smali.substringBefore("->")
    val rest = smali.substringAfter("->")
    val name = rest.substringBefore("(")
    val params = rest.substringAfter("(").substringBefore(")")
    val ret = rest.substringAfter(")")
    return ImmutableMethodReference(cls, name, splitTypes(params), ret)
}

private fun splitTypes(descriptor: String): List<String> {
    val types = mutableListOf<String>()
    var i = 0
    while (i < descriptor.length) {
        val start = i
        while (descriptor[i] == '[') i++
        if (descriptor[i] == 'L') i = descriptor.indexOf(';', i)
        i++
        types += descriptor.substring(start, i)
    }
    return types
}

val Instruction.stringValue: String?
    get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

val Instruction.methodRef: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * Makes the value read right after the constant [literal] (typically a feature flag lookup such as
 * `flags.getBoolean(45415425L)`) always [value], by replacing the `move-result` that receives it.
 * Returns how many places were changed.
 */
fun MutableMethodImplementation.overrideLiteralResult(literal: Long, value: Boolean, within: Int = 6): Int {
    var changed = 0
    val all = instructions
    for (i in all.indices) {
        if ((all[i] as? com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction)?.wideLiteral != literal) continue
        val result = (i + 1..minOf(i + within, all.lastIndex)).firstOrNull { all[it].opcode == Opcode.MOVE_RESULT } ?: continue
        replaceWithConst4(result, if (value) 1 else 0)
        changed++
    }
    return changed
}
