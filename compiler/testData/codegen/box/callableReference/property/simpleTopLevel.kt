// IGNORE_BACKEND: JVM_IR
// IGNORE_BACKEND: JS_IR
data class Box(val value: String)

val foo = Box("lol")

fun box(): String {
    val property = ::foo
    if (property.get() != Box("lol")) return "Fail value: ${property.get()}"
    if (property.name != "foo") return "Fail name: ${property.name}"
    return "OK"
}
