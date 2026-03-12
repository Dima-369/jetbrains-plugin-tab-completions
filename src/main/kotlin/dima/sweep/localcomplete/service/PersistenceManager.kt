package dima.sweep.localcomplete.service

import com.intellij.openapi.application.PathManager
import dima.sweep.localcomplete.model.FileRecord
import dima.sweep.localcomplete.model.IndexedLine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object PersistenceManager {
    private const val VERSION = 5
    private val indexFile: Path = Path.of(PathManager.getSystemPath(), "local-line-complete", "index.bin")

    fun save(records: List<FileRecord>) {
        Files.createDirectories(indexFile.parent)
        val tmpFile = indexFile.resolveSibling("index.bin.tmp")
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmpFile))).use { output ->
            output.writeInt(VERSION)
            output.writeInt(records.size)
            for (record in records) {
                output.writeUTF(record.absolutePath)
                output.writeUTF(record.extension)
                output.writeLong(record.lastIndexedTimestamp)
                output.writeLong(record.sizeBytes)
                output.writeInt(record.lines.size)
                for (line in record.lines) {
                    output.writeUTF(line.originalContent)
                    output.writeInt(line.lineNumber)
                    
                    output.writeInt(line.prefixContextHashes.size)
                    for (hash in line.prefixContextHashes) {
                        output.writeLong(hash)
                    }
                    
                    output.writeInt(line.suffixContextHashes.size)
                    for (hash in line.suffixContextHashes) {
                        output.writeLong(hash)
                    }
                }
            }
        }
        Files.move(tmpFile, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun load(): List<FileRecord> {
        if (!Files.exists(indexFile)) return emptyList()

        DataInputStream(BufferedInputStream(Files.newInputStream(indexFile))).use { input ->
            val version = input.readInt()
            if (version != VERSION) return emptyList()

            return List(input.readInt()) {
                val absolutePath = input.readUTF()
                val extension = input.readUTF()
                val lastIndexedTimestamp = input.readLong()
                val sizeBytes = input.readLong()
                val lines = List(input.readInt()) {
                    val originalContent = input.readUTF()
                    val lineNumber = input.readInt()
                    
                    val prefixHashCount = input.readInt()
                    val prefixContextHashes = List(prefixHashCount) { input.readLong() }
                    
                    val suffixHashCount = input.readInt()
                    val suffixContextHashes = List(suffixHashCount) { input.readLong() }
                    
                    IndexedLine(
                        normalizedContent = originalContent.trim(),
                        originalContent = originalContent,
                        leadingWhitespace = originalContent.takeWhile { ch -> ch == ' ' || ch == '\t' },
                        sourceFilePath = absolutePath,
                        lineNumber = lineNumber,
                        prefixContextHashes = prefixContextHashes,
                        suffixContextHashes = suffixContextHashes,
                    )
                }
                FileRecord(absolutePath, extension, lastIndexedTimestamp, lines, sizeBytes)
            }
        }
    }
}