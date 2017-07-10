package ksax

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.*
import javax.xml.parsers.SAXParserFactory

class KSAXRule<out T : Any>(val path: String, val converter: (String) -> Pair<String, T>)

class KSAXPostProcessRule<out T : Any>(val path: String, val converter: (HashMap<String, Any>) -> Pair<String, T>)

interface KSAXRuleBuilder {
    fun <T> push(pathToName: Pair<String, String>, optional: Boolean = false, converter: (String) -> T)
    fun push(pathToName: Pair<String, String>, optional: Boolean = false)
    fun push(tagName: String, optional: Boolean = false)
    fun <T> pushToList(pathToName: Pair<String, String>, optional: Boolean = false, converter: (HashMap<String, Any>) -> T)
}

class KSAXRuleBuilderImpl : KSAXRuleBuilder {

    private val internalNodeRules: HashMap<String, KSAXRule<Any>> = HashMap()
    private val internalAttrRules: HashMap<String, HashMap<String, KSAXRule<Any>>> = HashMap()
    private val internalPostProcessRules = HashMap<String, KSAXPostProcessRule<Any>>()

    private val internalAllRequiredRules = ArrayList<String>()

    val allRules: List<String>
        get() = internalAllRequiredRules

    val nodeRules: Map<String, KSAXRule<Any>>
        get() = internalNodeRules

    val attrRules: Map<String, Map<String, KSAXRule<*>>>
        get() = internalAttrRules

    val postProcessRules: Map<String, KSAXPostProcessRule<*>>
        get() = internalPostProcessRules

    override fun <T : Any> push(pathToName: Pair<String, String>, optional: Boolean, converter: (String) -> T) {
        val f: (String) -> Pair<String, T> = {
            pathToName.second to converter(it)
        }

        val rule = KSAXRule(pathToName.first, f)

        if (!optional) internalAllRequiredRules.add(pathToName.first)

        if (rule.path.contains('@')) {
            val node = rule.path.substringBefore('@')
            val attr = rule.path.substringAfter('@')
            internalAttrRules.putIfAbsent(node, HashMap())
            val attrRules = internalAttrRules[node]
            attrRules!![attr] = rule
        } else {
            internalNodeRules[rule.path] = rule
        }
    }

    override fun push(pathToName: Pair<String, String>, optional: Boolean) {
        val f: (String) -> String = { it }
        push(pathToName, optional, f)
    }

    override fun push(tagName: String, optional: Boolean) = push(tagName to tagName, optional)

    override fun <T : Any> pushToList(pathToName: Pair<String, String>, optional: Boolean, converter: (HashMap<String, Any>) -> T) {
        val f: (HashMap<String, Any>) -> Pair<String, T> = {
            pathToName.second to converter(it)
        }
        val rule = KSAXPostProcessRule(pathToName.first, f)
        if (!optional) internalAllRequiredRules.add(pathToName.first)
        internalPostProcessRules[pathToName.first] = rule
    }
}

fun HashMap<String, Any>.pop(key: String): String? = run {
    val result = remove(key)
    result as String?
}

fun HashMap<String, Any>.popNonNull(key: String): String = this.pop(key)!!


private class KSAXRuleProcessor<out T : Any>(
        val ruleBuilder: KSAXRuleBuilderImpl,
        val notFoundExFactory: (Collection<String>) -> RuntimeException,
        val toManyValuesExFactory: (String) -> RuntimeException,
        val block: (Map<String, Any>) -> T)
{
    companion object {
        private val saxFactory = SAXParserFactory.newInstance()
    }

    fun process(inputStream: InputStream): T {
        val handler = KSAXHandler(ruleBuilder, toManyValuesExFactory)
        saxFactory.newSAXParser().parse(inputStream, handler)
        val nonProcessedRules = handler.nonProcessedRules
        if (nonProcessedRules.isNotEmpty()) throw notFoundExFactory(nonProcessedRules)
        return block(handler.ctx)
    }
}

private val defaultExFactory: (Collection<String>) -> RuntimeException = {
    RuntimeException("Rules ${it.joinToString(",")} are not processed.")
}

private val defaultToManyValuesExFactory: (String) -> RuntimeException = {
    RuntimeException("A value of $it found more than once")
}

class KSAXParser<out T : Any>(
        private val ruleBuilder: KSAXRuleBuilderImpl,
        private val resultBuilder: (Map<String, Any>) -> T,
        private var exFactory: (Collection<String>) -> RuntimeException = defaultExFactory,
        private var tooManyValuesExFactory: (String) -> RuntimeException = defaultToManyValuesExFactory
) : KSAXRuleBuilder by ruleBuilder {
    fun parse(inputStream: InputStream): T = KSAXRuleProcessor(ruleBuilder, exFactory, tooManyValuesExFactory, resultBuilder).process(inputStream)
    fun <R : Any> withResultBuilder(block: (Map<String, Any>) -> R): KSAXParser<R> = KSAXParser(ruleBuilder, block, exFactory, tooManyValuesExFactory)
    fun <E : RuntimeException> withExFactory(block: (Collection<String>) -> E) {
        this.exFactory = block
    }
    fun <E: RuntimeException> withTooManyValuesExFactory(block: (String) -> E) {
        this.tooManyValuesExFactory = block
    }
}

private val defaultResultBuilder: (Map<String, Any>) -> Map<String, Any> = { it }

fun <R : Any> parseRules(block: KSAXParser<Map<String, Any>>.() -> KSAXParser<R>): KSAXParser<R> {
    val helper = KSAXParser(KSAXRuleBuilderImpl(), defaultResultBuilder)
    return helper.run(block)
}

private fun Attributes.toList() = (0..length - 1).map { getQName(it) to getValue(it) }

private class KSAXHandler(ruleBuilder: KSAXRuleBuilderImpl, val exFactory: (String) -> RuntimeException) : DefaultHandler() {
    private val valueRules = ruleBuilder.nodeRules
    private val attributeRules = ruleBuilder.attrRules
    private val postProcessRules = ruleBuilder.postProcessRules
    private val allRules = ruleBuilder.allRules
    private val processedRules = HashSet<String>()
    private val deq = ArrayDeque<String>()
    private var data: StringBuilder? = null
    private var currentPath = ""
    internal val ctx = HashMap<String, Any>()

    val nonProcessedRules: List<String>
        get() = allRules.minus(processedRules)

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        start(qName)
        if (valueRules.containsKey(currentPath)) {
            data = StringBuilder()
        } else {
            data = null
        }

        val rules = attributeRules[currentPath].orEmpty()
        if (rules.isEmpty()) return

        attributes.toList()
                .filter {
                    rules.containsKey(it.first)
                }
                .forEach {
                    val rule = rules[it.first]
                    add(rule!!.converter(it.second))
                    processedRules.add("${currentPath}@${it.first}")
                }
    }

    private fun start(qName: String) {
        deq.add(qName)
        currentPath = deq.joinToString(separator = "/")
    }

    private fun end() {
        deq.pollLast()
        currentPath = deq.joinToString(separator = "/")
    }

    fun add(namedValue: Pair<String, Any>) {
        if (ctx.putIfAbsent(namedValue.first, namedValue.second) != null) throw exFactory(namedValue.first)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> addToList(namedValue: Pair<String, T>) {
        ctx.putIfAbsent(namedValue.first, ArrayList<T>())
        val values = ctx[namedValue.first] as ArrayList<T>
        values.add(namedValue.second)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        Optional.ofNullable(valueRules[currentPath]).ifPresent {
            val s = data.toString().trim()
            add(it.converter(s))
            processedRules.add(it.path)
        }

        Optional.ofNullable(postProcessRules[currentPath]).ifPresent {
            val namedValue = it.converter(ctx)
            addToList(namedValue)
            processedRules.add(it.path)
        }
        end()
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        data?.append(ch, start, length)
    }
}
