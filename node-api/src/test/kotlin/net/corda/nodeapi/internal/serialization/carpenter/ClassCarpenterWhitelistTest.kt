package net.corda.nodeapi.internal.serialization.carpenter

import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.CordaSerializable
import org.junit.Test
import org.assertj.core.api.Assertions
import java.io.NotSerializableException

class ClassCarpenterWhitelistTest {

    // whitelisting a class on the class path will mean we will carpente up a class that
    // contains it as a member
    @Test
    fun whitelisted() {
        data class A (val a: Int)

        class WL : ClassWhitelist {
            private val allowedClasses = hashSetOf<String>(
                    A::class.java.name
            )

            override fun hasListed(type: Class<*>): Boolean = type.name in allowedClasses
        }

        val cc = ClassCarpenter(whitelist = WL())

        // if this works, the test works, if it throws then we're in a world of pain, we could
        // go further but there are a lot of other tests that test weather we can build
        // carpented objects
        cc.build(ClassSchema("thing", mapOf ("a" to NonNullableField(A::class.java))))
    }

    // However, a class on the class path that isn't whitelisted we will not create
    // an object that contains a member of that type
    @Test
    fun notWhitelisted() {
        data class A (val a: Int)

        class WL : ClassWhitelist {
            override fun hasListed(type: Class<*>) = false
        }

        val cc = ClassCarpenter(whitelist = WL())

        // Class A isn't on the whitelist, so we should fail to carpent it
        Assertions.assertThatThrownBy {
            cc.build(ClassSchema("thing", mapOf ("a" to NonNullableField(A::class.java))))
        }.isInstanceOf(NotSerializableException::class.java)
    }

    // despite now being whitelisted and on the class path, we will carpent this because
    // it's marked as CordaSerializable
    @Test
    fun notWhitelistedButAnnotated() {
        @CordaSerializable
        data class A (val a: Int)

        class WL : ClassWhitelist {
            override fun hasListed(type: Class<*>) = false
        }

        val cc = ClassCarpenter(whitelist = WL())

        // again, simply not throwing here is enough to show the test worked and the carpenter
        // didn't reject the type even though it wasn't on the whitelist because it was
        // annotated properly
        cc.build(ClassSchema("thing", mapOf ("a" to NonNullableField(A::class.java))))
    }

    // Even though it's not white listed because it's not on the class path and a class we've
    // carpetned we will carpent the enclosing class... if we didn't we couldnt' actually use
    // the class carpenter
    //
    // XXX this workd because it's marking everything as CordaSerializble in the carpenter,
    //
    // which is great except for this test, need to change the schema to alow greater
    // flexabiilyt of annotations, which really means doing generic annotations
    @Test
    fun notWhitelistedButCarpented() {
        // just have the white list reject *Everything*
        class WL : ClassWhitelist {
            override fun hasListed(type: Class<*>) = type.name == "int"
        }

        val cc = ClassCarpenter(whitelist = WL())

        val schema1 = ClassSchema("thing1", mapOf ("a" to NonNullableField(Int::class.java)))

        // without this thing1 will not be marked as CordaSerializable
        schema1.setNoSimpleFieldAccess()
        val clazz1 = cc.build(schema1)

        clazz1.declaredAnnotations.forEach {
            println(it)
        }
        println ("\n\n")

        val clazz2 = cc.build(ClassSchema("thing2", mapOf ("a" to NonNullableField(clazz1))))
    }
}