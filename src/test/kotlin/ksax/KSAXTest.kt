package ksax

import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal


data class Book(val id: String, val author: String, val title: String, val price: BigDecimal)

class TooManyValuesException(message: String) : RuntimeException(message)

class KSAXTest {
    companion object {
        val CATALOG = "catalog"
        val BOOK = "$CATALOG/book"
        val BOOKS = "Books"
        val BOOK_ID = "$BOOK@id"
        val BOOK_AUTHOR = "$BOOK/author"
        val BOOK_TITLE = "$BOOK/title"
        val BOOK_PRICE = "$BOOK/price"

        val data = read("books.xml")
    }


    @Suppress("UNCHECKED")
    val parser = xmlRules {
        store(BOOK_ID)
        store(BOOK_AUTHOR)
        store(BOOK_TITLE)
        store(BOOK_PRICE)

        storeToList(BOOK to BOOKS) { ctx ->
            Book(
                    id = ctx.popNonNull(BOOK_ID),
                    author = ctx.popNonNull(BOOK_AUTHOR),
                    title = ctx.popNonNull(BOOK_TITLE),
                    price = BigDecimal(ctx.pop(BOOK_PRICE) ?: "0")
            )
        }

        withTooManyValuesExFactory(::TooManyValuesException)
                                                                                        
        @Suppress("UNCHECKED_CAST")
        withResultBuilder { ctx ->
            ctx[BOOKS] as List<Book>
        }
    }

    fun doTest() {
        ByteArrayInputStream(data).use {
            val parsedObjects = parser.parse(it)
            assertThat(parsedObjects).hasSize(12)
            assertThat(parsedObjects.filter { it.price == BigDecimal.ZERO }).hasSize(2)
        }
    }

    @Test
    fun test() {
        val start = System.currentTimeMillis()
        doTest()
        println("Elapsed ${System.currentTimeMillis() - start} msec.")
    }

    class MyEx(message: String): RuntimeException(message)

    @Test
    fun `test with non-existent rules`() {
        val parser = xmlRules {
            store("a/b/c")
            store("a/b/c@d")
            storeToList("a/b" to "B") {
                it
            }

            withExFactory { MyEx(it.joinToString("|")) }

            withResultBuilder { it }
        }

        try {
            parser.parse(ByteArrayInputStream(data))
            fail()
        } catch (e: MyEx) {
            listOf("a/b/c", "a/b/c@d", "a/b").forEach {
                assertThat(e.message).contains(it)
            }
        }
    }

    @Test(expected = SAXParseException::class)
    fun `test with invalid XML`() {
        parser.parse(ByteArrayInputStream("12345".toByteArray()))
    }

    @Test
    fun `when duplicated values found on same parent node parsing should fails`() {
        try {
            parser.parse(ByteArrayInputStream(read("books-with-duplicated-nodes.xml")))
        } catch(e: TooManyValuesException) {
            assertThat(e.message).contains(BOOK_AUTHOR)
        }
    }

    @Test(expected = SAXParseException::class)
    fun `when found duplicated attrs on same node parsing should fails`() {
        parser.parse(ByteArrayInputStream(read("books-with-duplicated-attributes.xml")))
    }

    @Test
    @Ignore
    fun performanceTest() {
        val count = 500000
        var total = 0L

        (1..count).forEach {
            total += measureTime { doTest() }
        }

        println("Average time is ${total / count.toDouble()} msec.")
    }

    fun measureTime(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }
}

private fun read(resourceName: String) = run {
    val output = ByteArrayOutputStream()
    KSAXTest::class.java.classLoader.getResourceAsStream(resourceName)!!.use {
        it.copyTo(output)
    }
    output.toByteArray()
}