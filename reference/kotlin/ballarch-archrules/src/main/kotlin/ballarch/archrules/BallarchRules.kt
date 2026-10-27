package ballarch.archrules

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import ballarch.kernel.Ball
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.jvmErasure

/**
 * Architecture fitness functions for Ballarch Core 2 author rules.
 *
 * A *decider package* is any package that contains a class implementing
 * [Ball]. Everything in such a package is decider code and must be pure,
 * because helpers in the package are called from `decide`.
 *
 * What these rules can and cannot prove is stated on each rule. They check
 * bytecode dependencies and Kotlin property types; they do not prove
 * termination, and they cannot see effects hidden behind interfaces that are
 * passed in (the kernel gives deciders no such interfaces).
 */
public object BallarchRules {
    /** Packages that deciders must not use: I/O, networking, databases, concurrency, the runtime. */
    public val forbiddenPackages: List<String> = listOf(
        "java.io..", "java.nio..", "java.net..", "java.sql..", "javax..",
        "java.util.concurrent..", "java.lang.reflect..", "kotlin.io..", "kotlin.random..", "kotlinx.coroutines..",
        "ballarch.runtime..",
    )

    /** Individual classes that are ambient sources of time, randomness, identity or environment. */
    public val forbiddenClasses: List<String> = listOf(
        "java.lang.System", "java.lang.Thread", "java.lang.Runtime", "java.lang.ProcessBuilder",
        "java.util.Random", "java.security.SecureRandom", "java.util.UUID", "java.time.Clock",
    )

    public fun deciderPackages(classes: JavaClasses): Set<String> =
        classes.filter { it.isAssignableTo(Ball::class.java) && !it.isInterface }.mapTo(sortedSetOf()) { it.packageName }

    private fun inDeciderPackages(classes: JavaClasses): DescribedPredicate<JavaClass> {
        val packages = deciderPackages(classes)
        return DescribedPredicate.describe("reside in a decider package $packages") { it.packageName in packages }
    }

    /**
     * R2 (purity), bytecode part: decider code does not depend on I/O,
     * concurrency, reflection, the runtime, or ambient time/randomness/identity.
     * `java.time` value types are allowed; calls to their `now()` methods are not.
     */
    public fun deciderPurity(classes: JavaClasses): ArchRule =
        noClasses().that(inDeciderPackages(classes))
            .should().dependOnClassesThat().resideInAnyPackage(*forbiddenPackages.toTypedArray())
            .orShould().dependOnClassesThat(DescribedPredicate.describe("are one of $forbiddenClasses") { it.name in forbiddenClasses })
            .orShould(callNowOnJavaTime())
            .because("deciders must be pure functions of state, input and context (Core 2 rule R2)")

    private fun callNowOnJavaTime(): ArchCondition<JavaClass> = object : ArchCondition<JavaClass>("call java.time now()") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            for (call in item.methodCallsFromSelf) {
                val owner = call.targetOwner
                // Used under noClasses(): "satisfied" means the forbidden call was found.
                if (owner.packageName.startsWith("java.time") && call.name == "now") {
                    events.add(SimpleConditionEvent.satisfied(call, "${item.name} reads the clock: ${describe(call)}"))
                }
            }
        }
    }

    /** R1/R2: no global mutable state in decider code (non-final static fields). */
    public fun noGlobalMutableState(classes: JavaClasses): ArchRule =
        classes().that(inDeciderPackages(classes))
            .should(object : ArchCondition<JavaClass>("have no non-final static fields") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    for (f in item.fields) {
                        if (JavaModifier.STATIC in f.modifiers && JavaModifier.FINAL !in f.modifiers) {
                            events.add(SimpleConditionEvent.violated(f, "${item.name}.${f.name} is mutable global state"))
                        }
                    }
                }
            })
            .because("state must be owned by one Ball instance and changed only by commits (Core 2 rule R1)")

    /**
     * R1: state and message types in decider packages are immutable: no `var`
     * properties and no properties of mutable collection types. Uses Kotlin
     * metadata, so it applies to Kotlin classes only.
     */
    public fun immutableValues(classes: JavaClasses): ArchRule =
        classes().that(inDeciderPackages(classes))
            .should(object : ArchCondition<JavaClass>("expose only immutable properties") {
                override fun check(item: JavaClass, events: ConditionEvents) {
                    val k = runCatching { item.reflect().kotlin }.getOrNull() ?: return
                    if (k.java.isSynthetic || k.java.isAnonymousClass || !isKotlin(k.java)) return
                    val props = runCatching { k.declaredMemberProperties }.getOrNull() ?: return
                    for (p in props) {
                        if (p is kotlin.reflect.KMutableProperty<*>) {
                            events.add(SimpleConditionEvent.violated(item, "${item.name}.${p.name} is a var"))
                        }
                        val type = p.returnType.toString()
                        if (mutableTypes.any { type.startsWith(it) } || p.returnType.jvmErasure.java.name in mutableJavaTypes) {
                            events.add(SimpleConditionEvent.violated(item, "${item.name}.${p.name} has mutable type $type"))
                        }
                    }
                }
            })
            .because("committed state is shared with readers and must not change after commit (Core 2 rule R1)")

    private val mutableTypes = listOf(
        "kotlin.collections.MutableList", "kotlin.collections.MutableSet", "kotlin.collections.MutableMap",
        "kotlin.collections.MutableCollection", "kotlin.collections.ArrayList", "kotlin.collections.HashMap",
        "kotlin.collections.HashSet", "kotlin.collections.LinkedHashMap", "kotlin.collections.LinkedHashSet",
        "kotlin.Array", "kotlin.IntArray", "kotlin.LongArray", "kotlin.ByteArray",
    )
    private val mutableJavaTypes = setOf(
        "java.util.ArrayList", "java.util.HashMap", "java.util.HashSet", "java.util.LinkedHashMap", "java.util.LinkedList",
        "java.lang.StringBuilder",
    )

    private fun isKotlin(c: Class<*>) = c.isAnnotationPresent(Metadata::class.java)

    /** The kernel depends only on the Kotlin standard library, so it can move to Kotlin Multiplatform. */
    public fun kernelIsSelfContained(): ArchRule =
        classes().that().resideInAPackage("ballarch.kernel..")
            .should().onlyDependOnClassesThat(
                DescribedPredicate.describe("are Kotlin standard library types (java.util collections as mapped on the JVM)") { c ->
                    val p = c.packageName
                    p.startsWith("ballarch.kernel") || p == "kotlin" || p.startsWith("kotlin.") || p == "java.lang" ||
                        p == "java.lang.invoke" || p.startsWith("org.jetbrains.annotations") ||
                        (p == "java.util" && c.simpleName in kotlinMappedCollections) ||
                        // A marker interface without behaviour: an intent in application state is serializable.
                        c.fullName == "java.io.Serializable"
                },
            )
            .because("deciders depend only on the kernel, which must stay platform-neutral")

    /** java.util types that Kotlin's read-only collection types map to on the JVM. */
    private val kotlinMappedCollections = setOf("List", "Collection", "Set", "Map", "Iterator", "ListIterator", "Map\$Entry")

    /**
     * R10 at package level: a feature's implementation depends on other features
     * only through their public API packages.
     *
     * @param features feature root package -> API package (e.g. `shop.payments` -> `shop.payments.api`).
     */
    public fun featuresUseOnlyPublicApis(features: Map<String, String>): ArchRule =
        classes().should(object : ArchCondition<JavaClass>("depend on other features only through their API packages") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val own = features.keys.filter { item.packageName == it || item.packageName.startsWith("$it.") }.maxByOrNull { it.length }
                    ?: return
                for (dep in item.directDependenciesFromSelf) {
                    val target = dep.targetClass
                    val other = features.keys.filter { target.packageName == it || target.packageName.startsWith("$it.") }.maxByOrNull { it.length }
                        ?: continue
                    if (other == own || own.startsWith("$other.") || other.startsWith("$own.")) continue
                    val api = features.getValue(other)
                    if (!(target.packageName == api || target.packageName.startsWith("$api."))) {
                        events.add(SimpleConditionEvent.violated(dep, "${item.name} uses ${target.name}, an implementation class of feature $other"))
                    }
                }
            }
        }).because("features communicate only through public protocols (Core 2 rule R10)")

    /** Every rule that applies to a set of decider classes. */
    public fun all(classes: JavaClasses): List<ArchRule> = listOf(
        deciderPurity(classes),
        noGlobalMutableState(classes),
        immutableValues(classes),
    )

    private fun describe(a: JavaAccess<*>) = "${a.target.fullName} (${a.sourceCodeLocation})"
}
