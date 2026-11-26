package ru.vanyafitness.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val programA = listOf(
    "Жим от груди в тренажёре" to "Грудь",
    "Жим гантелей на наклонной скамье" to "Грудь",
    "Тяга верхнего блока" to "Спина",
    "Горизонтальная тяга" to "Спина",
    "Сгибание рук на бицепс" to "Бицепс",
    "Разгибание рук на трицепс" to "Трицепс"
)
private val programB = listOf(
    "Жим ногами" to "Ноги", "Выпады" to "Ноги", "Сведение ног" to "Ноги",
    "Тяга к подбородку" to "Плечи", "Задняя дельта" to "Плечи"
)
private data class EntryInput(val weight: String = "", val reps: String = "", val count: Int = 3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = FitnessDatabase.get(this).dao()
        lifecycleScope.launch { seedLog(dao) }
        setContent { VanyaFitnessApp(dao) }
    }
}

private suspend fun seedLog(dao: FitnessDao) {
    if (dao.count() > 0) return
    val id17 = dao.addWorkout(Workout(number = 17, date = "Дата уточняется", program = "Ноги и плечи"))
    val saved = listOf(
        Triple("Жим ногами", "Ноги", listOf(80.0 to 10, 80.0 to 12, 80.0 to 15)),
        Triple("Выпады", "Ноги", listOf(34.0 to 10, 34.0 to 12, 34.0 to 10)),
        Triple("Сведение ног", "Ноги", listOf(30.0 to 15, 35.0 to 12, 35.0 to 10)),
        Triple("Тяга к подбородку", "Плечи", listOf(24.0 to 13, 24.0 to 11, 24.0 to 12)),
        Triple("Задняя дельта", "Плечи", listOf(35.0 to 12, 40.0 to 12, 40.0 to 12))
    )
    saved.forEach { (name, group, sets) ->
        val eid = dao.addExercise(Exercise(workoutId = id17, name = name, muscleGroup = group))
        dao.addSets(sets.mapIndexed { i, p -> TrainingSet(exerciseId = eid, setNumber = i + 1, weightKg = p.first, reps = p.second) })
    }
    val id18 = dao.addWorkout(Workout(number = 18, date = "Дата уточняется", program = "Грудь, спина и руки"))
    programA.forEach { (name, group) -> dao.addExercise(Exercise(workoutId = id18, name = name, muscleGroup = group)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VanyaFitnessApp(dao: FitnessDao) {
    val scope = rememberCoroutineScope()
    val workouts by dao.observeAll().collectAsState(initial = emptyList())
    var page by remember { mutableIntStateOf(0) }
    var program by remember { mutableStateOf("Грудь, спина и руки") }
    var date by remember { mutableStateOf(today()) }
    var selected by remember { mutableStateOf(programA) }
    val inputs = remember { mutableStateMapOf<String, EntryInput>() }
    var status by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF193746))) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("VANYA FITNESS") }) },
            bottomBar = {
                NavigationBar {
                    listOf("Главная", "Тренировка", "История").forEachIndexed { index, label ->
                        NavigationBarItem(selected = page == index, onClick = { page = index },
                            icon = {}, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            when (page) {
                0 -> Column(Modifier.padding(padding).padding(16.dp)) {
                    Text("Тренировочный дневник Вани", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Следующая тренировка №${(workouts.maxOfOrNull { it.workout.number } ?: 18) + 1}")
                    Text("Программа А: грудь, спина и руки")
                    Text("Программа B: ноги и плечи")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { page = 1 }) { Text("Начать тренировку") }
                    if (status.isNotBlank()) Text(status)
                }
                1 -> Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("Новая тренировка", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(date, { date = it }, label = { Text("Дата ГГГГ-ММ-ДД") }, modifier = Modifier.fillMaxWidth())
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded, { expanded = it }) {
                        OutlinedTextField(program, {}, readOnly = true, label = { Text("Программа") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded, { expanded = false }) {
                            listOf("Грудь, спина и руки", "Ноги и плечи").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    program = option
                                    selected = if (option == "Ноги и плечи") programB else programA
                                    expanded = false
                                })
                            }
                        }
                    }
                    selected.forEach { (name, group) ->
                        val input = inputs[name] ?: EntryInput()
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text("$name · $group", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(input.weight, { inputs[name] = input.copy(weight = it) },
                                    label = { Text("Вес, кг") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(input.reps, { inputs[name] = input.copy(reps = it) },
                                    label = { Text("Повторения") }, modifier = Modifier.fillMaxWidth())
                                Row {
                                    Text("Подходов: ${input.count}", modifier = Modifier.weight(1f))
                                    TextButton(onClick = { inputs[name] = input.copy(count = (input.count - 1).coerceAtLeast(1)) }) { Text("−") }
                                    TextButton(onClick = { inputs[name] = input.copy(count = input.count + 1) }) { Text("+") }
                                }
                            }
                        }
                    }
                    Button(onClick = {
                        scope.launch {
                            val valid = selected.mapNotNull { pair ->
                                val entry = inputs[pair.first] ?: EntryInput()
                                val w = entry.weight.replace(',', '.').toDoubleOrNull()
                                val r = entry.reps.toIntOrNull()
                                if (w != null && w >= 0 && r != null && r > 0) Triple(pair, w, r to entry.count) else null
                            }
                            if (valid.isEmpty()) {
                                status = "Введи вес и повторения хотя бы для одного упражнения."
                            } else {
                                val number = (dao.maxNumber() ?: 18) + 1
                                val workoutId = dao.addWorkout(Workout(number = number, date = date, program = program))
                                valid.forEach { (pair, weight, repsAndCount) ->
                                    val exerciseId = dao.addExercise(Exercise(workoutId = workoutId, name = pair.first, muscleGroup = pair.second))
                                    val (reps, count) = repsAndCount
                                    dao.addSets((1..count).map { setNo ->
                                        TrainingSet(exerciseId = exerciseId, setNumber = setNo, weightKg = weight, reps = reps)
                                    })
                                }
                                status = "Тренировка №$number сохранена."
                                inputs.clear()
                                page = 2
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить тренировку") }
                    Text("В этой версии вес и повторы одинаковы для всех подходов одного упражнения. Раздельный ввод подходов добавим следующим обновлением.",
                        style = MaterialTheme.typography.bodySmall)
                }
                else -> LazyColumn(Modifier.padding(padding).padding(12.dp)) {
                    items(workouts) { workout ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Тренировка №${workout.workout.number} · ${workout.workout.program}",
                                    style = MaterialTheme.typography.titleMedium)
                                Text(workout.workout.date)
                                workout.exercises.forEach { exercise ->
                                    Text(exercise.exercise.name + " · " + exercise.exercise.muscleGroup)
                                    exercise.sets.forEach { set ->
                                        if (set.reps > 0) Text("Подход ${set.setNumber}: ${set.weightKg} кг × ${set.reps}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
