val counter: Int = cache.updateOrSet("COUNTER", { 1 }, 1::plus)
writeOutput("counter: $counter")
