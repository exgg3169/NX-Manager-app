package nx.cli

import nx.patcher.PatchOptions
import nx.patcher.Patcher
import nx.patcher.SigningKey
import nx.patches.youtube.YouTube
import java.io.File

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "patch" -> {
            val input = File(args[1])
            val output = File(args[2])
            val keyDir = File(args.getOrNull(3) ?: "nx-key")
            val start = System.currentTimeMillis()
            Patcher.patch(
                input, output, YouTube.patches, PatchOptions(), SigningKey.loadOrCreate(keyDir),
                File(output.parentFile ?: File("."), "nx-work"),
            ) { println(it) }
            println("Süre: ${(System.currentTimeMillis() - start) / 1000}s")
        }
        "verify" -> println(Patcher.verify(File(args[1]), YouTube.SIGNING_CERTS) ?: "OK")
        "literal" -> Analyze.literal(File(args[1]), java.lang.Long.decode(args[2]))
        "sig" -> Analyze.signature(File(args[1]), args[2], args[3])
        "impl" -> Analyze.implementers(File(args[1]), args[2])
        "grep" -> Analyze.grep(File(args[1]), args[2])
        "dump" -> Analyze.dump(File(args[1]), args[2])
        else -> println("usage: patch <in.apk> <out.apk> [keyDir] | grep <apk> <regex> | dump <apk> <Lclass;>")
    }
}
