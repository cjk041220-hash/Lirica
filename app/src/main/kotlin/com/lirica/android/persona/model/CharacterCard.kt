package com.lirica.android.persona.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CharacterCard(
    val spec: String = "chara_card_v3",
    @SerialName("spec_version")
    val specVersion: String = "3.0",
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes")
    val firstMes: String = "",
    @SerialName("mes_example")
    val mesExample: String = "",
    @SerialName("creator_notes")
    val creatorNotes: String = "",
    @SerialName("system_prompt")
    val systemPrompt: String = "",
    @SerialName("post_history_instructions")
    val postHistoryInstructions: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    @SerialName("character_version")
    val characterVersion: String = "1.0.0",
    @SerialName("alternate_greetings")
    val alternateGreetings: List<String> = emptyList(),
    @SerialName("character_book")
    val characterBook: CharacterBook? = null
)

@Serializable
data class CharacterBook(
    val name: String? = null,
    val description: String? = null,
    @SerialName("scan_depth")
    val scanDepth: Int? = null,
    @SerialName("token_budget")
    val tokenBudget: Int? = null,
    @SerialName("recursive_scanning")
    val recursiveScanning: Boolean? = null,
    val entries: List<CharacterBookEntry> = emptyList()
)

@Serializable
data class CharacterBookEntry(
    val id: Int? = null,
    val keys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    @SerialName("insertion_order")
    val insertionOrder: Int = 100,
    @SerialName("case_sensitive")
    val caseSensitive: Boolean = false,
    val constant: Boolean = false,
    val selective: Boolean = false,
    @SerialName("secondary_keys")
    val secondaryKeys: List<String> = emptyList()
)

/**
 * SillyTavern V2 / V3 Envelope DTO for JSON serialization / deserialization
 */
@Serializable
data class CharacterCardEnvelope(
    val spec: String? = null,
    @SerialName("spec_version")
    val specVersion: String? = null,
    val data: CharacterCardData? = null
)

@Serializable
data class CharacterCardData(
    val name: String? = null,
    val description: String? = null,
    val personality: String? = null,
    val scenario: String? = null,
    @SerialName("first_mes")
    val firstMes: String? = null,
    @SerialName("mes_example")
    val mesExample: String? = null,
    @SerialName("creator_notes")
    val creatorNotes: String? = null,
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    @SerialName("post_history_instructions")
    val postHistoryInstructions: String? = null,
    val tags: List<String> = emptyList(),
    val creator: String? = null,
    @SerialName("character_version")
    val characterVersion: String? = null,
    @SerialName("alternate_greetings")
    val alternateGreetings: List<String> = emptyList(),
    @SerialName("character_book")
    val characterBook: CharacterBook? = null
)
