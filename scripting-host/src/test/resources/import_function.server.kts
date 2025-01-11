@file:Import("imports/to_json")


class Output(val message: String, val code: Int)

val output = Output("OK", 200).toJson()

writeOutput(output)
