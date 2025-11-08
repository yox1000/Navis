package com.navis.pepscout.nav

import android.util.Log

/**
 * Formats navigation steps into short, friendly pet-style instructions
 * Converts technical directions into conversational guidance
 */
class StepFormatter {
    
    companion object {
        private const val TAG = "StepFormatter"
        private const val MAX_INSTRUCTION_LENGTH = 60 // ~7 seconds when spoken
    }

    /**
     * Format indoor navigation steps into pet-style instructions
     */
    fun formatIndoorSteps(steps: List<IndoorGraph.PathStep>): List<String> {
        return steps.mapIndexed { index, step ->
            formatIndoorStep(step, isFirst = index == 0, isLast = index == steps.size - 1)
        }
    }

    /**
     * Format outdoor OSRM steps into pet-style instructions
     */
    fun formatOutdoorSteps(steps: List<String>): List<String> {
        return steps.map { formatOutdoorStep(it) }
    }

    private fun formatIndoorStep(
        step: IndoorGraph.PathStep, 
        isFirst: Boolean, 
        isLast: Boolean
    ): String {
        return when {
            isFirst -> formatFirstStep(step)
            isLast -> formatLastStep(step)
            step.floorChange -> formatFloorChangeStep(step)
            else -> formatRegularStep(step)
        }.take(MAX_INSTRUCTION_LENGTH)
    }

    private fun formatFirstStep(step: IndoorGraph.PathStep): String {
        val baseInstruction = when {
            step.toNode.type == "lobby" -> "Let's head into the main lobby!"
            step.toNode.type == "corridor" -> "Follow me down the hallway!"
            step.instruction.contains("entrance", ignoreCase = true) -> "Enter through the main doors!"
            else -> "Let's get started! ${step.instruction}"
        }
        return baseInstruction
    }

    private fun formatLastStep(step: IndoorGraph.PathStep): String {
        return when {
            step.toNode.type == "destination" -> "We're here! Welcome to ${step.toNode.name}!"
            step.toNode.name.contains("reference", ignoreCase = true) -> "Found it! The reference section!"
            step.toNode.name.contains("study", ignoreCase = true) -> "Perfect! Your study room is ready!"
            else -> "We made it! Here's ${step.toNode.name}!"
        }
    }

    private fun formatFloorChangeStep(step: IndoorGraph.PathStep): String {
        return when {
            step.requiresStairs -> "Up the stairs we go to floor ${step.toNode.floor}!"
            step.toNode.type == "elevator" && step.toNode.floor > step.fromNode.floor ->
                "Hop in the elevator! Going up to floor ${step.toNode.floor}!"
            step.toNode.type == "elevator" && step.toNode.floor < step.fromNode.floor ->
                "Time to go down! Elevator to floor ${step.toNode.floor}!"
            else -> "Let's head to floor ${step.toNode.floor}!"
        }
    }

    private fun formatRegularStep(step: IndoorGraph.PathStep): String {
        val instruction = step.instruction.lowercase()
        
        return when {
            "straight" in instruction -> "Keep going straight ahead!"
            "left" in instruction -> "Turn left here!"
            "right" in instruction -> "Turn right this way!"
            "elevator" in instruction -> "Head to the elevator!"
            "corridor" in instruction || "hallway" in instruction -> "Down this hallway!"
            "desk" in instruction -> "Over to the service desk!"
            "north" in instruction -> "Head north!"
            "south" in instruction -> "Head south!"
            "east" in instruction -> "Go east!"
            "west" in instruction -> "Go west!"
            else -> simplifyInstruction(step.instruction)
        }
    }

    private fun formatOutdoorStep(originalStep: String): String {
        val step = originalStep.lowercase()
        
        return when {
            step.contains("head") && step.contains("north") -> "Let's go north!"
            step.contains("head") && step.contains("south") -> "Let's go south!"
            step.contains("head") && step.contains("east") -> "Head east!"
            step.contains("head") && step.contains("west") -> "Head west!"
            step.contains("turn left") -> "Turn left here!"
            step.contains("turn right") -> "Turn right here!"
            step.contains("continue") && step.contains("straight") -> "Keep going straight!"
            step.contains("arrive") -> "We're almost there!"
            step.contains("destination") -> "Here we are!"
            step.contains("sidewalk") || step.contains("path") -> "Follow the path!"
            step.contains("cross") && step.contains("street") -> "Careful crossing the street!"
            step.contains("cross") && step.contains("road") -> "Let's cross the road!"
            step.contains("bus stop") -> "There's the bus stop!"
            step.contains("building") -> "Head to the building!"
            else -> simplifyInstruction(originalStep)
        }.take(MAX_INSTRUCTION_LENGTH)
    }

    private fun simplifyInstruction(instruction: String): String {
        // Remove technical jargon and simplify
        var simplified = instruction
            .replace("proceed", "go", ignoreCase = true)
            .replace("continue", "keep going", ignoreCase = true)
            .replace("approximately", "about", ignoreCase = true)
            .replace("meters", "m", ignoreCase = true)
            .replace("towards", "to", ignoreCase = true)
            .replace("direction", "", ignoreCase = true)
            .trim()
        
        // Ensure it starts with a friendly tone
        if (!simplified.startsWith("let's", ignoreCase = true) &&
            !simplified.startsWith("head", ignoreCase = true) &&
            !simplified.startsWith("go", ignoreCase = true) &&
            !simplified.startsWith("turn", ignoreCase = true) &&
            !simplified.startsWith("keep", ignoreCase = true)) {
            simplified = "Let's $simplified"
        }
        
        // Ensure it ends with enthusiasm
        if (!simplified.endsWith("!") && !simplified.endsWith(".")) {
            simplified += "!"
        }
        
        return simplified.take(MAX_INSTRUCTION_LENGTH)
    }

    /**
     * Generate safety warning phrases for hazard events
     */
    fun formatSafetyWarning(hazardType: String, severity: String): String {
        return when (severity) {
            "danger" -> when (hazardType) {
                "person" -> "Careful! Person ahead!"
                "chair" -> "Watch out! Chair in the way!"
                "bike" -> "Heads up! Bike coming!"
                else -> "Danger! Obstacle ahead!"
            }
            "warn" -> when (hazardType) {
                "person" -> "Person ahead, take care!"
                "chair" -> "Chair ahead, mind your step!"
                "bike" -> "Bike nearby, stay alert!"
                else -> "Something ahead, careful!"
            }
            else -> when (hazardType) {
                "person" -> "Someone ahead!"
                "chair" -> "Chair nearby!"
                "bike" -> "Bike around!"
                else -> "Object detected!"
            }
        }.take(MAX_INSTRUCTION_LENGTH)
    }

    /**
     * Generate encouraging completion phrases
     */
    fun formatCompletionMessage(destination: String): String {
        val messages = listOf(
            "Great job! You made it to $destination!",
            "Perfect! Welcome to $destination!",
            "Wonderful! You've arrived at $destination!",
            "Excellent! Here's $destination!"
        )
        return messages.random().take(MAX_INSTRUCTION_LENGTH)
    }

    /**
     * Generate error or help messages in pet style
     */
    fun formatHelpMessage(situation: String): String {
        return when (situation.lowercase()) {
            "qr_failed" -> "No worries! Use the Next button instead!"
            "off_path" -> "Oops! Let's get back on track!"
            "connection_lost" -> "Lost signal, but we can still navigate offline!"
            "no_route" -> "Hmm, can't find a path. Let's try a different way!"
            else -> "Don't worry! I'm here to help!"
        }.take(MAX_INSTRUCTION_LENGTH)
    }
}