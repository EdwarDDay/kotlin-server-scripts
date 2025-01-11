
val sharedInvocation: Int = cache.updateOrSet("INVOCATION", { 1 }, 1::plus)

writeOutput("shared invocation counter: $sharedInvocation\n")
