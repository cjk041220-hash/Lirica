package com.xiaoxi.android.persona

import com.xiaoxi.android.persona.model.CharacterCard
import com.xiaoxi.android.persona.model.CharacterCardData
import com.xiaoxi.android.persona.model.CharacterCardEnvelope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

object CharacterCardDecoder {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun decodeFromPng(bytes: ByteArray): CharacterCard {
        return decodeFromInputStream(ByteArrayInputStream(bytes))
    }

    fun decodeFromInputStream(inputStream: InputStream): CharacterCard {
        val dis = DataInputStream(inputStream)
        val signature = ByteArray(8)
        dis.readFully(signature)
        if (!signature.contentEquals(PNG_SIGNATURE)) {
            throw IllegalArgumentException("Invalid PNG signature")
        }

        while (dis.available() > 0) {
            val length = dis.readInt()
            val typeBytes = ByteArray(4)
            dis.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            val data = ByteArray(length)
            dis.readFully(data)
            dis.readInt() // Skip CRC

            when (type) {
                "tEXt" -> {
                    val nullIndex = data.indexOf(0)
                    if (nullIndex > 0) {
                        val keyword = String(data, 0, nullIndex, Charsets.ISO_8859_1)
                        if (keyword.equals("chara", ignoreCase = true) || keyword.equals("ccv3", ignoreCase = true)) {
                            val rawText = String(data, nullIndex + 1, data.size - nullIndex - 1, Charsets.UTF_8)
                            return decodeTextPayload(rawText)
                        }
                    }
                }
                "zTXt" -> {
                    val nullIndex = data.indexOf(0)
                    if (nullIndex > 0 && nullIndex + 2 < data.size) {
                        val keyword = String(data, 0, nullIndex, Charsets.ISO_8859_1)
                        if (keyword.equals("chara", ignoreCase = true) || keyword.equals("ccv3", ignoreCase = true)) {
                            // data[nullIndex + 1] is compression method (0 = deflate)
                            val compressedData = data.copyOfRange(nullIndex + 2, data.size)
                            val decompressed = InflaterInputStream(ByteArrayInputStream(compressedData)).readBytes()
                            val rawText = String(decompressed, Charsets.UTF_8)
                            return decodeTextPayload(rawText)
                        }
                    }
                }
                "IEND" -> break
            }
        }

        throw IllegalArgumentException("No SillyTavern character card found in PNG")
    }

    private fun decodeTextPayload(text: String): CharacterCard {
        val trimmed = text.trim()
        val jsonString = try {
            val decodedBytes = Base64.getDecoder().decode(trimmed)
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            trimmed
        }
        return decodeFromJson(jsonString)
    }

    fun decodeFromJson(jsonString: String): CharacterCard {
        return try {
            val envelope = json.decodeFromString<CharacterCardEnvelope>(jsonString)
            if (envelope.data != null) {
                fromData(envelope.spec ?: "chara_card_v2", envelope.specVersion ?: "2.0", envelope.data)
            } else {
                json.decodeFromString<CharacterCard>(jsonString)
            }
        } catch (e: Exception) {
            json.decodeFromString<CharacterCard>(jsonString)
        }
    }

    private fun fromData(spec: String, specVersion: String, data: CharacterCardData): CharacterCard {
        return CharacterCard(
            spec = spec,
            specVersion = specVersion,
            name = data.name ?: "",
            description = data.description ?: "",
            personality = data.personality ?: "",
            scenario = data.scenario ?: "",
            firstMes = data.firstMes ?: "",
            mesExample = data.mesExample ?: "",
            creatorNotes = data.creatorNotes ?: "",
            systemPrompt = data.systemPrompt ?: "",
            postHistoryInstructions = data.postHistoryInstructions ?: "",
            tags = data.tags,
            creator = data.creator ?: "",
            characterVersion = data.characterVersion ?: "1.0.0",
            alternateGreetings = data.alternateGreetings,
            characterBook = data.characterBook
        )
    }

    fun encodeToPng(basePngBytes: ByteArray, card: CharacterCard): ByteArray {
        val dis = DataInputStream(ByteArrayInputStream(basePngBytes))
        val signature = ByteArray(8)
        dis.readFully(signature)
        if (!signature.contentEquals(PNG_SIGNATURE)) {
            throw IllegalArgumentException("Invalid base PNG signature")
        }

        val envelope = CharacterCardEnvelope(
            spec = card.spec,
            specVersion = card.specVersion,
            data = CharacterCardData(
                name = card.name,
                description = card.description,
                personality = card.personality,
                scenario = card.scenario,
                firstMes = card.firstMes,
                mesExample = card.mesExample,
                creatorNotes = card.creatorNotes,
                systemPrompt = card.systemPrompt,
                postHistoryInstructions = card.postHistoryInstructions,
                tags = card.tags,
                creator = card.creator,
                characterVersion = card.characterVersion,
                alternateGreetings = card.alternateGreetings,
                characterBook = card.characterBook
            )
        )

        val jsonBytes = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        val base64Text = Base64.getEncoder().encodeToString(jsonBytes)

        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)

        // Read and copy all chunks except IEND and old chara/ccv3 tEXt chunks
        while (dis.available() > 0) {
            val length = dis.readInt()
            val typeBytes = ByteArray(4)
            dis.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            val data = ByteArray(length)
            dis.readFully(data)
            dis.readInt() // CRC

            if (type == "tEXt") {
                val nullIndex = data.indexOf(0)
                if (nullIndex > 0) {
                    val kw = String(data, 0, nullIndex, Charsets.ISO_8859_1)
                    if (kw.equals("chara", ignoreCase = true) || kw.equals("ccv3", ignoreCase = true)) {
                        continue // Skip old character card chunk
                    }
                }
            }

            if (type == "IEND") {
                // Insert our new ccv3 tEXt chunk right before IEND
                writeTextChunk(out, "ccv3", base64Text)
                writeChunk(out, "IEND", ByteArray(0))
                break
            } else {
                writeChunk(out, type, data)
            }
        }

        return out.toByteArray()
    }

    private fun writeTextChunk(out: ByteArrayOutputStream, keyword: String, text: String) {
        val chunkData = ByteArrayOutputStream().apply {
            write(keyword.toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(text.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        writeChunk(out, "tEXt", chunkData)
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.write(typeBytes)
        dos.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
    }
}
