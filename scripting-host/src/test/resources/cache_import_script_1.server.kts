@file:Import("imports/invocation_counter")

val invocation: Int = cache.updateOrSet("INVOCATION", { 1 }, 1::plus)

writeOutput("script invocation counter: $invocation")
