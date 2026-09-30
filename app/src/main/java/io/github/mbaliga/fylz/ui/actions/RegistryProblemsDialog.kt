package io.github.mbaliga.fylz.ui.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.mbaliga.fylz.actions.RegistryProblem

/**
 * `fylz.customisation.problems`'s own surface (design MC.0e, §2.3 clarification): a minimal
 * dialog listing `registry.problems`, one line each. Never shown for the shipped built-ins, whose
 * `registry.problems` is always empty (`ShortcutTableTest`); a user-defined action bundle (MC.1+)
 * is what could ever populate it.
 */
@Composable
fun RegistryProblemsDialog(problems: List<RegistryProblem>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customisation problems") },
        text = { Column { problems.forEach { problem -> Text(registryProblemLine(problem)) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

private fun registryProblemLine(problem: RegistryProblem): String = when (problem) {
    is RegistryProblem.ShortcutConflict ->
        "Shortcut conflict: ${problem.chord} is claimed by ${problem.ids.joinToString { it.value }}"
    is RegistryProblem.DuplicateId -> "Duplicate action id: ${problem.id.value}"
    is RegistryProblem.TargetRequiredOnTargetlessPlacement ->
        "${problem.id.value} needs a target on a placement that can't supply one: ${problem.placement}"
}
