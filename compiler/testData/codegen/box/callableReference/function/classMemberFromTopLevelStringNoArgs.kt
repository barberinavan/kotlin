// IGNORE_BACKEND: JVM_IR
class A {
    fun foo() = "OK"
}

fun box(): String {
    val x = A::foo
    return x(A())
}
