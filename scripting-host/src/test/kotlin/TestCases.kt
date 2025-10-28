package net.edwardday.serverscript.scripthost

interface TestCases {
    val fileNames: List<String>

    enum class Script(val fileName: String) : TestCases {
        BIG_OUTPUT("big_output"),
        CUSTOM_HEADERS("custom_headers"),
        DEPENDENCY("dependency"),
        DEPENDENCY_REPOSITORY("dependency_repository"),
        EMPTY("empty"),
        EXCEPTION("exception"),
        IMPORT_FUNCTION("import_function"),
        IMPORT_INVALID("import_invalid"),
        IMPORT_NOT_EXISTENT("import_not_existent"),
        IMPORT_SCRIPT("import_script"),
        INVALID("invalid"),
        KNOWN_STATUS("known_status"),
        MULTIPLE_OUTPUT("multiple_output"),
        SIMPLE_SCRIPT("simple_script"),
        UNKNOWN_STATUS("unknown_status"),
        ;

        override val fileNames: List<String> get() = listOf(fileName)
    }

    enum class CacheScript(override val fileNames: List<String>) : TestCases {
        CACHE_DATA(List(5) { "cache_data" }),
        CACHE_IMPORT_SCRIPT(listOf("cache_import_script_1", "cache_import_script_2")),
        ;
    }
}