package com.navis.pepscout.intent

import android.content.Context
import android.util.Log
import com.navis.pepscout.data.KnownPlace
import com.navis.pepscout.data.KnownPlacesStore
import com.navis.pepscout.data.Keystore
import com.navis.pepscout.net.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Intent-to-target pipeline for voice navigation
 * Pipeline: STT -> Gemini classify -> Rerank -> Target selection
 */
class IntentToTargetPipeline(
    private val context: Context,
    private val knownPlacesStore: KnownPlacesStore,
    private val geminiClient: GeminiClient,
    private val keystore: Keystore
) {
    
    companion object {
        private const val TAG = "IntentToTargetPipeline"
        private const val MAX_CANDIDATES = 5
        private const val MIN_CONFIDENCE_THRESHOLD = 0.3
    }
    
    private val cohereReranker = CohereReranker(keystore)
    private val localReranker = LocalReranker()
    
    /**
     * Process voice transcript to navigation target
     */
    suspend fun processTranscript(transcript: String): IntentResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing transcript: $transcript")
                
                // Step 1: Classify intent with Gemini
                val classification = geminiClient.classifyIntent(transcript)
                if (classification == null) {
                    return@withContext IntentResult.Error("Failed to classify intent")
                }
                
                Log.d(TAG, "Intent classification: ${classification.action}")
                
                when (classification.action) {
                    "navigate" -> processNavigationIntent(transcript, classification.to?.name)
                    "qa" -> IntentResult.QuestionIntent(classification.question ?: transcript)
                    else -> IntentResult.UnknownIntent(transcript)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing transcript", e)
                IntentResult.Error("Failed to process voice command", e)
            }
        }
    }
    
    /**
     * Process navigation intent with reranking
     */
    private suspend fun processNavigationIntent(
        transcript: String, 
        extractedLocation: String?
    ): IntentResult {
        val query = extractedLocation ?: transcript
        
        // Get initial candidates from known places
        val candidates = knownPlacesStore.searchPlaces(query, MAX_CANDIDATES)
        
        if (candidates.isEmpty()) {
            return IntentResult.NoTargetFound("No matching locations found for: $query")
        }
        
        // Apply reranking if we have multiple candidates
        val rerankedCandidates = if (candidates.size > 1) {
            applyReranking(query, candidates)
        } else {
            candidates
        }
        
        val topCandidate = rerankedCandidates.firstOrNull()
        
        return if (topCandidate != null && topCandidate.score >= MIN_CONFIDENCE_THRESHOLD) {
            IntentResult.NavigationTarget(
                place = topCandidate.place,
                confidence = topCandidate.score.toFloat(),
                query = query,
                alternatives = rerankedCandidates.drop(1).take(3)
            )
        } else {
            IntentResult.LowConfidenceTarget(
                candidates = rerankedCandidates.take(3),
                query = query
            )
        }
    }
    
    /**
     * Apply reranking using Cohere API or local fallback
     */
    private suspend fun applyReranking(
        query: String,
        candidates: List<com.navis.pepscout.data.ScoredPlace>
    ): List<com.navis.pepscout.data.ScoredPlace> {
        return try {
            // Try Cohere reranking first
            if (keystore.hasApiKey(Keystore.COHERE_API_KEY)) {
                cohereReranker.rerank(query, candidates)
            } else {
                // Fallback to local reranking
                Log.d(TAG, "Using local reranking (no Cohere key)")
                localReranker.rerank(query, candidates)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reranking failed, using original order", e)
            candidates
        }
    }
}

/**
 * Cohere API reranker
 */
class CohereReranker(private val keystore: Keystore) {
    
    companion object {
        private const val TAG = "CohereReranker"
        private const val COHERE_API_URL = "https://api.cohere.ai/v1/rerank"
    }
    
    suspend fun rerank(
        query: String,
        candidates: List<com.navis.pepscout.data.ScoredPlace>
    ): List<com.navis.pepscout.data.ScoredPlace> {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = keystore.getCohereKey()
                if (apiKey.isNullOrBlank()) {
                    throw Exception("Cohere API key not available")
                }
                
                // TODO: Implement actual Cohere API call
                // For now, use local reranking as fallback
                Log.d(TAG, "Cohere reranking not implemented, using local fallback")
                LocalReranker().rerank(query, candidates)
                
            } catch (e: Exception) {
                Log.w(TAG, "Cohere reranking failed", e)
                candidates
            }
        }
    }
}

/**
 * Local reranker using enhanced scoring
 */
class LocalReranker {
    
    companion object {
        private const val TAG = "LocalReranker"
    }
    
    fun rerank(
        query: String,
        candidates: List<com.navis.pepscout.data.ScoredPlace>
    ): List<com.navis.pepscout.data.ScoredPlace> {
        try {
            // Enhanced local scoring with semantic matching
            val reranked = candidates.map { candidate ->
                val enhancedScore = calculateEnhancedScore(query, candidate.place)
                com.navis.pepscout.data.ScoredPlace(candidate.place, enhancedScore)
            }
            
            return reranked.sortedByDescending { it.score }
            
        } catch (e: Exception) {
            Log.w(TAG, "Local reranking failed", e)
            return candidates
        }
    }
    
    private fun calculateEnhancedScore(query: String, place: KnownPlace): Double {
        val queryLower = query.lowercase().trim()
        var score = 0.0
        
        // Exact matches get highest priority
        if (place.name.lowercase() == queryLower) {
            score += 1000.0
        }
        
        // Synonym exact matches
        place.synonyms.forEach { synonym ->
            if (synonym.lowercase() == queryLower) {
                score += 800.0
            }
        }
        
        // Partial matches with position weighting
        val nameWords = place.name.lowercase().split(" ")
        val queryWords = queryLower.split(" ")
        
        queryWords.forEach { queryWord ->
            nameWords.forEachIndexed { index, nameWord ->
                when {
                    nameWord == queryWord -> score += 200.0 / (index + 1)
                    nameWord.startsWith(queryWord) -> score += 100.0 / (index + 1)
                    nameWord.contains(queryWord) -> score += 50.0 / (index + 1)
                }
            }
            
            // Check synonyms
            place.synonyms.forEach { synonym ->
                synonym.lowercase().split(" ").forEachIndexed { index, synWord ->
                    when {
                        synWord == queryWord -> score += 150.0 / (index + 1)
                        synWord.startsWith(queryWord) -> score += 75.0 / (index + 1)
                        synWord.contains(queryWord) -> score += 25.0 / (index + 1)
                    }
                }
            }
        }
        
        // Context and category bonuses
        if (place.category.lowercase().contains(queryLower)) {
            score += 30.0
        }
        
        // Penalize very long names for short queries
        if (queryWords.size <= 2 && nameWords.size > 4) {
            score *= 0.8
        }
        
        // Boost common destinations
        val commonDestinations = listOf("reading room", "study", "computer", "reference", "help")
        if (commonDestinations.any { it in place.name.lowercase() || queryLower.contains(it) }) {
            score *= 1.2
        }
        
        return score
    }
}

/**
 * Sealed class for intent processing results
 */
sealed class IntentResult {
    data class NavigationTarget(
        val place: KnownPlace,
        val confidence: Float,
        val query: String,
        val alternatives: List<com.navis.pepscout.data.ScoredPlace>
    ) : IntentResult()
    
    data class LowConfidenceTarget(
        val candidates: List<com.navis.pepscout.data.ScoredPlace>,
        val query: String
    ) : IntentResult()
    
    data class QuestionIntent(
        val question: String
    ) : IntentResult()
    
    data class NoTargetFound(
        val message: String
    ) : IntentResult()
    
    data class UnknownIntent(
        val transcript: String
    ) : IntentResult()
    
    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : IntentResult()
}