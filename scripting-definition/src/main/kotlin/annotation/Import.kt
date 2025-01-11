package net.edwardday.serverscript.scriptdefinition.annotation

@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class Import(val path: String)
