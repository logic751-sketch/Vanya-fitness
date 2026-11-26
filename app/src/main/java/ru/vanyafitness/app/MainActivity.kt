package ru.vanyafitness.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PROGRAM_A = "Грудь, спина и руки"
private const val PROGRAM_B = "Ноги и плечи"
private val PROGRAMS = listOf(PROGRAM_A, PROGRAM_B)

private data class SetDraft(val weight: String = "", val reps: String = "")
private data class TemplateDraft(
    val id: Long? = null,
    val program: String = PROGRAM_A,
    val name: String = "",
    val muscle: String = "Грудь"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = FitnessDatabase.get(this).dao()
        lifecycleScope.launch { seedTemplates(dao) }
        setContent { VanyaFitnessApp(dao) }
    }
}

private suspend fun seedTemplates(dao: FitnessDao) {
    if (dao.countTemplates() > 0) return
    val all = listOf(
        PROGRAM_A to listOf(
            "Жим от груди в тренажёре" to "Грудь",
            "Жим гантелей на наклонной скамье" to "Грудь",
            "Тяга верхнего блока" to "Спина",
            "Горизонтальная тяга" to "Спина",
            "Сгибание рук на бицепс" to "Бицепс",
            "Разгибание рук на трицепс" to "Трицепс"
        ),
        PROGRAM_B to listOf(
            "Жим ногами" to "Ноги",
            "Выпады" to "Ноги",
            "Сведение ног" to "Ноги",
            "Тяга к подбородку" to "Плечи",
            "Задняя дельта" to "Плечи"
        )
    )
    all.forEach { (program, exercises) ->
        exercises.forEachIndexed { index, pair ->
            dao.addTemplate(ExerciseTemplate(
                programName = program,
                name = pair.first,
                muscleGroup = pair.second,
                sortOrder = index
            ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VanyaFitnessApp(dao: FitnessDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workouts by dao.observeAll().collectAsState(initial = emptyList())
    val templates by dao.observeTemplates().collectAsState(initial = emptyList())

    var page by remember { mutableIntStateOf(0) }
    var selectedProgram by remember { mutableStateOf(PROGRAM_A) }
    var workoutDate by remember { mutableStateOf(today()) }
    var setDrafts by remember { mutableStateOf<Map<Long, List<SetDraft>>>(emptyMap()) }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<TemplateDraft?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingPhotoId by remember { mutableStateOf<Long?>(null) }
    var pendingPhotoForNew by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            scope.launch {
                val id = pendingPhotoId
                if (id != null) {
                    val item = templates.firstOrNull { it.id == id }
                    if (item != null) dao.updateTemplate(item.copy(imageUri = uri.toString()))
                } else if (pendingPhotoForNew) {
                    val draft = editing
                    if (draft != null) {
                        val newId = dao.addTemplate(ExerciseTemplate(
                            programName = draft.program,
                            name = draft.name.trim(),
                            muscleGroup = draft.muscle.trim(),
                            imageUri = uri.toString(),
                            sortOrder = templates.count { it.programName == draft.program }
                        ))
                        status = "Упражнение добавлено с фотографией."
                    }
                }
            }
        }
        pendingPhotoId = null
        pendingPhotoForNew = false
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF193746))) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("VANYA FITNESS 2.0") }) },
            bottomBar = {
                NavigationBar {
                    listOf("Главная", "Тренировка", "Упражнения", "История").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = page == index,
                            onClick = { page = index },
                            icon = {},
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            when (page) {
                0 -> Column(Modifier.padding(padding).padding(16.dp)) {
                    Text("Тренировочный дневник Вани", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Следующая тренировка №${(workouts.maxOfOrNull { it.workout.number } ?: 18) + 1}")
                    Text("Программ в библиотеке: ${templates.map { it.programName }.distinct().size}")
                    Text("Упражнений: ${templates.size}")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { page = 1 }, modifier = Modifier.fillMaxWidth()) {
                        Text("Начать тренировку")
                    }
                    OutlinedButton(onClick = { page = 2 }, modifier = Modifier.fillMaxWidth()) {
                        Text("Редактировать упражнения")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Версия 2.0: редактируемые программы, фотографии из галереи и отдельная запись каждого подхода.",
                        style = MaterialTheme.typography.bodySmall)
                    if (status.isNotBlank()) Text(status)
                }

                1 -> Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("Новая тренировка", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = workoutDate,
                        onValueChange = { workoutDate = it },
                        label = { Text("Дата ГГГГ-ММ-ДД") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded, { expanded = it }) {
                        OutlinedTextField(
                            value = selectedProgram,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Программа") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded, { expanded = false }) {
                            templates.map { it.programName }.distinct().forEach { program ->
                                DropdownMenuItem(text = { Text(program) }, onClick = {
                                    selectedProgram = program
                                    setDrafts = emptyMap()
                                    expanded = false
                                })
                            }
                        }
                    }

                    val chosen = templates.filter { it.programName == selectedProgram }.sortedBy { it.sortOrder }
                    if (chosen.isEmpty()) Text("В этой программе пока нет упражнений. Добавь их во вкладке «Упражнения».")
                    chosen.forEach { item ->
                        val rows = setDrafts[item.id] ?: List(3) { SetDraft() }
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(10.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(item.muscleGroup, style = MaterialTheme.typography.bodySmall)
                                if (item.imageUri != null) {
                                    AsyncImage(
                                        model = item.imageUri,
                                        contentDescription = "Фото: ${item.name}",
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp)
                                    )
                                }
                                rows.forEachIndexed { index, row ->
                                    Text("Подход ${index + 1}", style = MaterialTheme.typography.labelLarge)
                                    Row {
                                        OutlinedTextField(
                                            value = row.weight,
                                            onValueChange = { value ->
                                                val updated = rows.toMutableList()
                                                updated[index] = row.copy(weight = value)
                                                setDrafts = setDrafts + (item.id to updated)
                                            },
                                            label = { Text("Вес, кг") },
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedTextField(
                                            value = row.reps,
                                            onValueChange = { value ->
                                                val updated = rows.toMutableList()
                                                updated[index] = row.copy(reps = value)
                                                setDrafts = setDrafts + (item.id to updated)
                                            },
                                            label = { Text("Повторы") },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Row {
                                    TextButton(onClick = {
                                        setDrafts = setDrafts + (item.id to (rows + SetDraft()))
                                    }) { Text("Добавить подход +") }
                                    if (rows.size > 1) TextButton(onClick = {
                                        setDrafts = setDrafts + (item.id to rows.dropLast(1))
                                    }) { Text("Убрать последний") }
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val values = chosen.mapNotNull { item ->
                                    val rows = setDrafts[item.id] ?: emptyList()
                                    val parsed = rows.mapIndexedNotNull { index, row ->
                                        val weight = row.weight.replace(',', '.').toDoubleOrNull()
                                        val reps = row.reps.toIntOrNull()
                                        if (weight != null && weight >= 0 && reps != null && reps > 0)
                                            TrainingSet(exerciseId = 0, setNumber = index + 1, weightKg = weight, reps = reps)
                                        else null
                                    }
                                    if (parsed.isNotEmpty()) item to parsed else null
                                }
                                if (values.isEmpty()) {
                                    status = "Заполни вес и повторения хотя бы в одном подходе."
                                } else {
                                    val number = (dao.maxNumber() ?: 18) + 1
                                    val workoutId = dao.addWorkout(Workout(
                                        number = number, date = workoutDate, program = selectedProgram
                                    ))
                                    values.forEach { (template, sets) ->
                                        val exerciseId = dao.addExercise(Exercise(
                                            workoutId = workoutId,
                                            name = template.name,
                                            muscleGroup = template.muscleGroup
                                        ))
                                        dao.addSets(sets.map { it.copy(exerciseId = exerciseId) })
                                    }
                                    status = "Тренировка №$number сохранена."
                                    setDrafts = emptyMap()
                                    page = 3
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить тренировку") }
                    if (status.isNotBlank()) Text(status)
                }

                2 -> Column(Modifier.padding(padding).padding(12.dp)) {
                    Text("Библиотека упражнений", style = MaterialTheme.typography.headlineSmall)
                    Text("Редактируй названия, группы мышц, порядок и фотографии.")
                    Spacer(Modifier.height(8.dp))
                    PROGRAMS.forEach { program ->
                        val group = templates.filter { it.programName == program }.sortedBy { it.sortOrder }
                        Text(program, style = MaterialTheme.typography.titleLarge)
                        group.forEachIndexed { index, item ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                                    Text(item.muscleGroup, style = MaterialTheme.typography.bodySmall)
                                    if (item.imageUri != null) {
                                        AsyncImage(
                                            model = item.imageUri,
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                                        )
                                    }
                                    Row {
                                        TextButton(onClick = {
                                            editing = TemplateDraft(item.id, item.programName, item.name, item.muscleGroup)
                                            showEditor = true
                                        }) { Text("Изменить") }
                                        TextButton(onClick = {
                                            pendingPhotoId = item.id
                                            photoPicker.launch(arrayOf("image/*"))
                                        }) { Text(if (item.imageUri == null) "Добавить фото" else "Заменить фото") }
                                    }
                                    Row {
                                        TextButton(enabled = index > 0, onClick = {
                                            scope.launch {
                                                val previous = group[index - 1]
                                                dao.updateTemplate(item.copy(sortOrder = previous.sortOrder))
                                                dao.updateTemplate(previous.copy(sortOrder = item.sortOrder))
                                            }
                                        }) { Text("↑") }
                                        TextButton(enabled = index < group.lastIndex, onClick = {
                                            scope.launch {
                                                val next = group[index + 1]
                                                dao.updateTemplate(item.copy(sortOrder = next.sortOrder))
                                                dao.updateTemplate(next.copy(sortOrder = item.sortOrder))
                                            }
                                        }) { Text("↓") }
                                        TextButton(onClick = { scope.launch { dao.deleteTemplate(item) } }) {
                                            Text("Удалить")
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                editing = TemplateDraft(program = program, muscle = if (program == PROGRAM_B) "Ноги" else "Грудь")
                                showEditor = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Добавить упражнение в эту программу") }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (status.isNotBlank()) Text(status)
                }

                else -> LazyColumn(Modifier.padding(padding).padding(12.dp)) {
                    item { Text("История тренировок", style = MaterialTheme.typography.headlineSmall) }
                    items(workouts, key = { it.workout.id }) { workout ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "Тренировка №${workout.workout.number} · ${workout.workout.program}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(workout.workout.date)
                                workout.exercises.forEach { exercise ->
                                    Text(exercise.exercise.name + " · " + exercise.exercise.muscleGroup)
                                    exercise.sets.sortedBy { it.setNumber }.forEach { set ->
                                        Text("Подход ${set.setNumber}: ${set.weightKg} кг × ${set.reps}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor && editing != null) {
        val draft = editing!!
        var name by remember(draft.id) { mutableStateOf(draft.name) }
        var muscle by remember(draft.id) { mutableStateOf(draft.muscle) }
        var program by remember(draft.id) { mutableStateOf(draft.program) }
        AlertDialog(
            onDismissRequest = { showEditor = false; editing = null },
            title = { Text(if (draft.id == null) "Новое упражнение" else "Изменить упражнение") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") })
                    OutlinedTextField(muscle, { muscle = it }, label = { Text("Группа мышц") })
                    var open by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(open, { open = it }) {
                        OutlinedTextField(
                            value = program, onValueChange = {}, readOnly = true,
                            label = { Text("Программа") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(open, { open = false }) {
                            PROGRAMS.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { program = option; open = false })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && muscle.isNotBlank()) {
                        scope.launch {
                            if (draft.id == null) {
                                dao.addTemplate(ExerciseTemplate(
                                    programName = program,
                                    name = name.trim(),
                                    muscleGroup = muscle.trim(),
                                    sortOrder = templates.count { it.programName == program }
                                ))
                            } else {
                                val old = templates.firstOrNull { it.id == draft.id }
                                if (old != null) dao.updateTemplate(old.copy(
                                    programName = program, name = name.trim(), muscleGroup = muscle.trim(),
                                    sortOrder = if (old.programName == program) old.sortOrder else templates.count { it.programName == program }
                                ))
                            }
                            status = "Изменения сохранены."
                            showEditor = false
                            editing = null
                        }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false; editing = null }) { Text("Отмена") }
            }
        )
    }
}

private fun today(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
