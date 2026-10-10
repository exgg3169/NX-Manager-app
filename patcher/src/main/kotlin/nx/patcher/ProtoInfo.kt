package nx.patcher

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Reads which Java field holds which protobuf field number in a protobuf-lite message class.
 * Lite messages describe their layout in a compact string plus an object array passed to
 * `newMessageInfo`; obfuscation renames the fields but keeps the field numbers.
 */
object ProtoInfo {
    /** Field number to Java field name. Fields inside oneofs are left out. */
    fun fieldNumbers(cls: ClassDef): Map<Int, String> {
        for (m in cls.methods) {
            val ins = m.implementation?.instructions?.toList() ?: continue
            val call = ins.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "newMessageInfo" }
            if (call < 0) continue

            // Replays the register writes to learn the info string and the object array contents.
            val values = HashMap<Int, Any?>()
            // Elements are stored in index order, so the order of the stores is enough.
            val array = ArrayList<Any?>()
            var arrayRegister = -1
            for (i in 0 until call) {
                val cur = ins[i]
                val ref = (cur as? ReferenceInstruction)?.reference
                when (cur.opcode) {
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                        values[(cur as OneRegisterInstruction).registerA] = (ref as StringReference).string
                    Opcode.CONST_CLASS, Opcode.SGET_OBJECT ->
                        values[(cur as OneRegisterInstruction).registerA] = ref.toString()
                    Opcode.NEW_ARRAY -> {
                        arrayRegister = (cur as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA
                        array.clear()
                    }
                    Opcode.APUT_OBJECT -> {
                        cur as ThreeRegisterInstruction
                        if (cur.registerB == arrayRegister) array.add(values[cur.registerA])
                    }
                    else -> {}
                }
            }
            // The layout string starts with small control characters (flags, field count).
            val info = ins.subList(0, call).mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                .filter { it.length >= 2 && it[0].code < 0x20 }
                .maxByOrNull { it.length } ?: continue
            return decode(info, array)
        }
        return emptyMap()
    }

    private fun decode(info: String, objects: List<Any?>): Map<Int, String> {
        var pos = 0
        fun next(): Int {
            var v = info[pos++].code
            if (v < 0xD800) return v
            var result = v and 0x1FFF
            var shift = 13
            while (true) {
                v = info[pos++].code
                if (v < 0xD800) return result or (v shl shift)
                result = result or ((v and 0x1FFF) shl shift)
                shift += 13
            }
        }
        next() // flags
        val fieldCount = next()
        if (fieldCount == 0) return emptyMap()
        val oneofCount = next()
        val hasBitsCount = next()
        repeat(6) { next() } // min/max field number, entries, map/repeated counts, required count
        var o = oneofCount * 2 + hasBitsCount
        val result = LinkedHashMap<Int, String>()
        repeat(fieldCount) {
            val number = next()
            val typeBits = next()
            val type = typeBits and 0xFF
            if (type >= 51) { // oneof member
                next()
                if (type == 60 || type == 68 || (typeBits and 0x800 != 0 && type == 63)) o++
                return@repeat
            }
            val name = objects.getOrNull(o++) as? String
            when {
                type == 27 || type == 49 -> o++ // message class of a repeated message field
                type == 50 -> { o++; if (typeBits and 0x800 != 0) o++ } // map entry (+ enum verifier)
                typeBits and 0x800 != 0 && (type == 12 || type == 30 || type == 44) -> o++ // enum verifier
            }
            if (typeBits and 0x1000 != 0 && type <= 17) next() // has-bit index
            if (name != null) result[number] = name
        }
        return result
    }

    /** The field of [cls] holding protobuf field [number]. */
    fun field(cls: ClassDef, number: Int): FieldReference {
        val name = fieldNumbers(cls)[number] ?: throw PatchException("${cls.type}: protobuf field $number not found")
        return cls.fields.firstOrNull { it.name == name } ?: throw PatchException("${cls.type}: field $name not found")
    }
}
