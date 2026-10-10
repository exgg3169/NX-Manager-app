package nx.patcher

import com.android.tools.smali.dexlib2.base.reference.BaseMethodReference
import com.android.tools.smali.dexlib2.base.reference.BaseTypeReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.MethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * A class whose methods can be modified. Everything except the methods is taken
 * from the original class as is.
 */
class EditableClass(private val base: ClassDef) : BaseTypeReference(), ClassDef {
    private val directs: MutableList<EditableMethod> = base.directMethods.mapTo(ArrayList()) { EditableMethod(it) }
    private val virtuals: MutableList<EditableMethod> = base.virtualMethods.mapTo(ArrayList()) { EditableMethod(it) }

    override fun getType(): String = base.type
    override fun getAccessFlags(): Int = base.accessFlags
    override fun getSuperclass(): String? = base.superclass
    override fun getInterfaces(): List<String> = base.interfaces
    override fun getSourceFile(): String? = base.sourceFile
    override fun getAnnotations(): Set<Annotation> = base.annotations
    override fun getStaticFields(): Iterable<Field> = base.staticFields
    override fun getInstanceFields(): Iterable<Field> = base.instanceFields
    override fun getFields(): Iterable<Field> = base.fields
    override fun getDirectMethods(): Iterable<Method> = directs
    override fun getVirtualMethods(): Iterable<Method> = virtuals
    override fun getMethods(): Iterable<Method> = directs + virtuals

    val methodList: List<EditableMethod> get() = directs + virtuals

    /** Adds a new method (e.g. a bridge with a fixed name for our extension code). */
    fun addMethod(method: Method): EditableMethod {
        val editable = EditableMethod(method)
        val direct = com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(method.accessFlags) ||
            com.android.tools.smali.dexlib2.AccessFlags.PRIVATE.isSet(method.accessFlags) || method.name == "<init>"
        if (direct) directs += editable else virtuals += editable
        return editable
    }

    /** Finds the editable counterpart of [method], which may come from the original class. */
    fun methodLike(method: Method): EditableMethod = methodList.first {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
}

class EditableMethod(private val base: Method) : BaseMethodReference(), Method {
    private val params: List<MethodParameter> = base.parameters.map(ImmutableMethodParameter::of)
    private var impl: MethodImplementation? = base.implementation

    override fun getDefiningClass(): String = base.definingClass
    override fun getName(): String = base.name
    override fun getParameters(): List<MethodParameter> = params
    override fun getParameterTypes(): List<CharSequence> = params.map { it.type }
    override fun getReturnType(): String = base.returnType
    override fun getAccessFlags(): Int = base.accessFlags
    override fun getAnnotations(): Set<Annotation> = base.annotations
    override fun getHiddenApiRestrictions() = base.hiddenApiRestrictions
    override fun getImplementation(): MethodImplementation? = impl

    /** The method body in a form that supports inserting and replacing instructions. */
    val code: MutableMethodImplementation
        get() = impl as? MutableMethodImplementation
            ?: MutableMethodImplementation(impl ?: throw PatchException("$this has no code"))
                .also { impl = it }

    /** Replaces the whole method body. */
    fun setCode(implementation: MethodImplementation) {
        impl = implementation
    }
}

class PatchException(message: String) : Exception(message)
