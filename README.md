
# ksax
A small utility written in Kotlin and allowing to build rules of parsing and transforming XML using SAX.

## Usage sample

### Parser declaration
```kotlin

data class ParsedObject (
    @get:NotEmpty val cadastralNumber: String,
    @get:Valid val buyers: List<Name>,
    @get:NotEmpty val address: String
)


class SampleParser {
    companion object {
        val CADASTRAL_NUMBER = "KPOKS/Realty/Flat@CadastralNumber"
        val ADDRESS = "KPOKS/Realty/Flat/Address/adrOut4:Note"
        val BUYER_NAME = "KPOKS/ReestrExtract/ExtractObjectRight/ExtractObject/ObjectRight/Right/Owner/Person/FIO"
        val BUYER_FIRST_NAME = "$BUYER_NAME/First"
        val BUYER_PATRONYMIC = "$BUYER_NAME/Patronymic"
        val BUYER_LAST_NAME = "$BUYER_NAME/Surname"
        val BUYERS = "Buyers"
    }

    val parser = parseRules {
        push(CADASTRAL_NUMBER)
        push(ADDRESS)
        push(BUYER_LAST_NAME)
        push(BUYER_FIRST_NAME)
        push(BUYER_PATRONYMIC)
        
        pushToList(BUYER_NAME to BUYERS) { ctx ->
            Name(
                firstName = ctx.popNonNull(BUYER_FIRST_NAME),
                patronymic = ctx.pop(BUYER_PATRONYMIC),
                lastName = ctx.popNonNull(BUYER_LAST_NAME)
            )
        }

        @Suppress("UNCHECKED_CAST")
        withResultBuilder { ctx ->
            SecondaryEgrnExtract(
                cadastralNumber = ctx[CADASTRAL_NUMBER] as String,
                address = ctx[ADDRESS] as String,
                buyers = ctx[BUYERS] as List<Name>)
        }
    }

    fun parse(inputStream: InputStream) = parser.parse(inputStream.buffered())
}

```

And usage of parser:

```kotlin
  val result: ParsedObjecct = SampleParser(inputStream).parse()

```

