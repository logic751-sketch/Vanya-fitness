package ru.vanyafitness.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
        lifecycleScope.launch { seedTemplates(dao); seedDefaultProgram(dao); importWorkout19(dao) }
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


private suspend fun seedDefaultProgram(dao: FitnessDao) {
    if (dao.allPrograms().isNotEmpty()) return
    val programId = dao.addProgram(TrainingProgram(name = "Базовая программа", description = "Стартовый план Вани"))
    listOf(PROGRAM_A, PROGRAM_B).forEachIndexed { dayIndex, dayName ->
        val dayId = dao.addProgramDay(ProgramDay(programId = programId, name = dayName, sortOrder = dayIndex))
        dao.allTemplates().filter { it.programName == dayName }.sortedBy { it.sortOrder }
            .forEachIndexed { index, template ->
                dao.addProgramDayExercise(ProgramDayExercise(dayId = dayId, templateId = template.id, sortOrder = index))
            }
    }
}

/** Imports the supplied workout #19 once; existing history is never replaced. */
private suspend fun importWorkout19(dao: FitnessDao) {
    if (dao.allWorkouts().any { it.number == 19 }) return
    val workoutId = dao.addWorkout(Workout(
        number = 19,
        date = "2026-11-25",
        program = PROGRAM_A
    ))
    val entries = listOf(
        Triple("Жим от груди в тренажёре", "Грудь", listOf(30.0 to 8, 30.0 to 8, 30.0 to 8)),
        Triple("Бабочка", "Грудь", listOf(20.0 to 12, 20.0 to 12, 20.0 to 12)),
        Triple("Тяга верхнего блока", "Спина", listOf(35.0 to 10, 35.0 to 10, 35.0 to 8)),
        Triple("Тяга к животу в Смите", "Спина", listOf(35.0 to 12, 35.0 to 12, 35.0 to 12)),
        Triple("Сгибание рук изоляция на скамье", "Бицепс", listOf(15.0 to 11, 15.0 to 12, 15.0 to 10)),
        Triple("Разгибание рук на трицепс в тренажёре", "Трицепс", listOf(25.0 to 15, 27.5 to 12, 27.5 to 15))
    )
    entries.forEach { (name, muscle, sets) ->
        val exerciseId = dao.addExercise(Exercise(
            workoutId = workoutId,
            name = name,
            muscleGroup = muscle
        ))
        dao.addSets(sets.mapIndexed { index, set ->
            TrainingSet(
                exerciseId = exerciseId,
                setNumber = index + 1,
                weightKg = set.first,
                reps = set.second
            )
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VanyaFitnessApp(dao: FitnessDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workouts by dao.observeAll().collectAsState(initial = emptyList())
    val templates by dao.observeTemplates().collectAsState(initial = emptyList())
    val programs by dao.observePrograms().collectAsState(initial = emptyList())
    val programDays by dao.observeProgramDays().collectAsState(initial = emptyList())
    val dayExercises by dao.observeProgramDayExercises().collectAsState(initial = emptyList())

    var page by remember { mutableIntStateOf(0) }
    var selectedProgram by remember { mutableStateOf(PROGRAM_A) }
    var selectedProgramId by remember { mutableStateOf<Long?>(null) }
    var selectedDayId by remember { mutableStateOf<Long?>(null) }
    var workoutDate by remember { mutableStateOf(today()) }
    var setDrafts by remember { mutableStateOf<Map<Long, List<SetDraft>>>(emptyMap()) }
    var status by remember { mutableStateOf("") }
    var completedSummary by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TemplateDraft?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingPhotoId by remember { mutableStateOf<Long?>(null) }
    var pendingPhotoForNew by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try { BackupManager.export(context, FitnessDatabase.get(context), uri); status = "Резервная копия сохранена."
            } catch (_: Exception) { status = "Не удалось создать резервную копию."
            }
        }
    }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { BackupManager.restore(context, FitnessDatabase.get(context), uri); status = "Данные восстановлены из резервной копии."
            } catch (_: Exception) { status = "Не удалось восстановить данные из этого файла."
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    val extension = when {
                        mime.contains("png", ignoreCase = true) -> "png"
                        mime.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val photoFile = java.io.File(
                        context.filesDir,
                        "exercise_photo_${System.currentTimeMillis()}.$extension"
                    )
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        photoFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Не удалось открыть выбранное изображение")
                    val savedUri = Uri.fromFile(photoFile).toString()
                    val id = pendingPhotoId
                    if (id != null) {
                        val item = templates.firstOrNull { it.id == id }
                        if (item != null) {
                            dao.updateTemplate(item.copy(imageUri = savedUri))
                            status = "Фотография упражнения сохранена."
                        } else {
                            photoFile.delete()
                            status = "Упражнение не найдено. Обнови список и попробуй ещё раз."
                        }
                    } else if (pendingPhotoForNew) {
                        val draft = editing
                        if (draft != null) {
                            dao.addTemplate(ExerciseTemplate(
                                programName = draft.program,
                                name = draft.name.trim(),
                                muscleGroup = draft.muscle.trim(),
                                imageUri = savedUri,
                                sortOrder = templates.count { it.programName == draft.program }
                            ))
                            status = "Упражнение добавлено с фотографией."
                        } else {
                            photoFile.delete()
                            status = "Сначала заполни название упражнения."
                        }
                    } else {
                        photoFile.delete()
                        status = "Не удалось определить упражнение для фотографии."
                    }
                } catch (e: Exception) {
                    status = "Не удалось сохранить фото: ${e.localizedMessage ?: "ошибка доступа к файлу"}"
                } finally {
                    pendingPhotoId = null
                    pendingPhotoForNew = false
                }
            }
        } else {
            pendingPhotoId = null
            pendingPhotoForNew = false
        }
    }

    val darkColors = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFFF8A00),
        secondary = androidx.compose.ui.graphics.Color(0xFF19BCEB),
        background = androidx.compose.ui.graphics.Color(0xFF07131D),
        surface = androidx.compose.ui.graphics.Color(0xFF102536),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF101820),
        onBackground = androidx.compose.ui.graphics.Color(0xFFF4F7FA),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF4F7FA)
    )
    MaterialTheme(colorScheme = darkColors) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color(0xFF07131D),
            topBar = { TopAppBar(
                title = { Text("VANYA FITNESS", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF07131D),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            ) },
            bottomBar = {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color(0xFF0C1D2B)) {
                    listOf("Главная", "Тренировка", "Прогресс", "Ещё").forEachIndexed { index, label ->
                        val destination = listOf(0, 1, 4, 7)[index]
                        val selected = when (page) { 0 -> 0; 1 -> 1; 4 -> 2; else -> 3 }
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { page = destination },
                            icon = { Text(listOf("⌂", "🏋", "▥", "☰")[index]) },
                            label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = androidx.compose.ui.graphics.Color(0xFF263B4B),
                                unselectedIconColor = androidx.compose.ui.graphics.Color(0xFF9CB1C1),
                                unselectedTextColor = androidx.compose.ui.graphics.Color(0xFF9CB1C1)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (page) {
                0 -> HomeDashboard(
                    modifier = Modifier.padding(padding),
                    nextNumber = (workouts.maxOfOrNull { it.workout.number } ?: 18) + 1,
                    workoutCount = workouts.size,
                    exerciseCount = templates.size,
                    onStart = { page = 1 },
                    onHistory = { page = 3 },
                    onExercises = { page = 2 },
                    onAnalytics = { page = 4 },
                    onBackup = { page = 5 },
                    onProfile = { page = 6 },
                    lastWorkout = workouts.maxByOrNull { it.workout.number },
                    status = status
                )

                1 -> Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("Новая тренировка", style = MaterialTheme.typography.headlineSmall)
                    RestTimerCard()
                    OutlinedTextField(
                        value = workoutDate,
                        onValueChange = { workoutDate = it },
                        label = { Text("Дата ГГГГ-ММ-ДД") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val activeProgram = programs.firstOrNull { it.id == selectedProgramId } ?: programs.firstOrNull()
                    LaunchedEffect(programs) { if (selectedProgramId == null && programs.isNotEmpty()) selectedProgramId = programs.first().id }
                    val availableDays = programDays.filter { it.programId == activeProgram?.id }.sortedBy { it.sortOrder }
                    LaunchedEffect(availableDays) { if (selectedDayId !in availableDays.map { it.id }) selectedDayId = availableDays.firstOrNull()?.id }
                    var programMenu by remember { mutableStateOf(false) }
                    var dayMenu by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(programMenu, { programMenu = it }) {
                        OutlinedTextField(activeProgram?.name ?: "Нет программ", {}, readOnly = true,
                            label = { Text("Программа") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(programMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(programMenu, { programMenu = false }) {
                            programs.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                selectedProgramId = p.id; selectedDayId = null; setDrafts = emptyMap(); programMenu = false
                            }) }
                        }
                    }
                    ExposedDropdownMenuBox(dayMenu, { dayMenu = it }) {
                        OutlinedTextField(availableDays.firstOrNull { it.id == selectedDayId }?.name ?: "Нет тренировочных дней", {},
                            readOnly = true, label = { Text("Тренировочный день") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dayMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(dayMenu, { dayMenu = false }) {
                            availableDays.forEach { d -> DropdownMenuItem(text = { Text(d.name) }, onClick = {
                                selectedDayId = d.id; setDrafts = emptyMap(); dayMenu = false
                            }) }
                        }
                    }

                    val chosenIds = dayExercises.filter { it.dayId == selectedDayId }.sortedBy { it.sortOrder }.map { it.templateId }.toSet()
                    val chosen = templates.filter { it.id in chosenIds }.sortedBy { template ->
                        dayExercises.firstOrNull { it.dayId == selectedDayId && it.templateId == template.id }?.sortOrder ?: Int.MAX_VALUE
                    }
                    if (chosen.isEmpty()) Text("В этой программе пока нет упражнений. Добавь их во вкладке «Упражнения».")
                    chosen.forEach { item ->
                        val previousSets = remember(item.id, workouts) {
                            workouts.asSequence().sortedByDescending { it.workout.number }
                                .flatMap { it.exercises.asSequence() }
                                .firstOrNull { it.exercise.name.equals(item.name, true) }
                                ?.sets?.sortedBy { it.setNumber }
                                ?.map { SetDraft(weight = it.weightKg.toString().removeSuffix(".0"), reps = it.reps.toString()) }
                                .orEmpty()
                        }
                        val rows = setDrafts[item.id] ?: if (previousSets.isNotEmpty()) previousSets else List(3) { SetDraft() }
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
                                        number = number, date = workoutDate, program = "${activeProgram?.name ?: "Программа"} · ${availableDays.firstOrNull { it.id == selectedDayId }?.name ?: "День"}"
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
                                    completedSummary = "Тренировка №$number · ${activeProgram?.name ?: "Программа"} / ${availableDays.firstOrNull { it.id == selectedDayId }?.name ?: "День"}\nДата: $workoutDate\nУпражнений: ${values.size}\nПодходов: ${values.sumOf { it.second.size }}\nОбщий объём: ${values.sumOf { pair -> pair.second.sumOf { it.weightKg * it.reps } }.toInt()} кг·повт."
                                    setDrafts = emptyMap()
                                    page = 3
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить тренировку") }
                    if (status.isNotBlank()) Text(status)
                }

                2 -> Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
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

                3 -> LazyColumn(Modifier.padding(padding).padding(12.dp)) {
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
                4 -> AnalyticsScreen(workouts)
                8 -> ProgramBuilderScreen(Modifier.padding(padding), dao, programs, programDays, dayExercises, templates, status) { status = it }
                6 -> ProfileProgressScreen(Modifier.padding(padding), workouts)
                7 -> MoreMenuScreen(
                    modifier = Modifier.padding(padding),
                    onHistory = { page = 3 },
                    onExercises = { page = 2 },
                    onAnalytics = { page = 4 },
                    onBackup = { page = 5 },
                    onProfile = { page = 6 }
                )
                5 -> Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Резервная копия", style = MaterialTheme.typography.headlineSmall)
                    Text("Сохрани историю тренировок, упражнения, подходы и библиотеку упражнений в отдельный файл.")
                    Button(onClick = { backupExport.launch("vanya_fitness_backup.json") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Создать резервную копию")
                    }
                    OutlinedButton(onClick = { showRestoreConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Восстановить из копии")
                    }
                    Text("Восстановление заменит текущие записи. Перед ним обязательно создай свежую копию текущих данных.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (status.isNotBlank()) Text(status)
                }
            }
        }
    }

    if (completedSummary != null) {
        AlertDialog(onDismissRequest = { completedSummary = null },
            title = { Text("Тренировка завершена! 💪") },
            text = { Text(completedSummary!!) },
            confirmButton = { TextButton(onClick = { completedSummary = null }) { Text("Отлично") } })
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Восстановить данные?") },
            text = { Text("Текущая история и библиотека будут заменены данными из выбранного файла. Продолжай только если у тебя есть свежая резервная копия.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    backupImport.launch(arrayOf("application/json", "text/json", "*/*"))
                }) { Text("Выбрать файл") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Отмена") } }
        )
    }

    if (showEditor && editing != null) {
        val draft = editing!!
        var name by remember(draft.id) { mutableStateOf(draft.name) }
        var muscle by remember(draft.id) { mutableStateOf(draft.muscle) }
        var program by remember(draft.id) { mutableStateOf(draft.program) }
        var muscleMenu by remember { mutableStateOf(false) }
        var programMenu by remember { mutableStateOf(false) }
        val muscleOptions = listOf("Грудь", "Спина", "Плечи", "Бицепс", "Трицепс", "Предплечья", "Ноги", "Ягодицы", "Икры", "Пресс")
        Dialog(onDismissRequest = { showEditor = false; editing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(max = 680.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (draft.id == null) "Новое упражнение" else "Изменить упражнение",
                        style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(name, { name = it }, label = { Text("Название упражнения") },
                        modifier = Modifier.fillMaxWidth())
                    ExposedDropdownMenuBox(muscleMenu, { muscleMenu = it }) {
                        OutlinedTextField(muscle, { muscle = it; muscleMenu = true },
                            label = { Text("Группа мышц — можно вписать свою") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(muscleMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true)
                        ExposedDropdownMenu(muscleMenu, { muscleMenu = false }) {
                            muscleOptions.filter { muscle.isBlank() || it.contains(muscle, true) }.forEach { option ->
                                DropdownMenuItem(text = { Text(option) },
                                    onClick = { muscle = option; muscleMenu = false })
                            }
                        }
                    }
                    ExposedDropdownMenuBox(programMenu, { programMenu = it }) {
                        OutlinedTextField(program, {}, readOnly = true, label = { Text("Программа тренировки") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(programMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(programMenu, { programMenu = false }) {
                            PROGRAMS.forEach { option -> DropdownMenuItem(text = { Text(option) },
                                onClick = { program = option; programMenu = false }) }
                        }
                    }
                    Text("Группу мышц можно выбрать или вписать вручную. Всё окно прокручивается.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(modifier = Modifier.weight(1f), onClick = { showEditor = false; editing = null }) { Text("Отмена") }
                        Button(modifier = Modifier.weight(1f), enabled = name.isNotBlank() && muscle.isNotBlank(), onClick = {
                            scope.launch {
                                if (draft.id == null) {
                                    dao.addTemplate(ExerciseTemplate(programName = program, name = name.trim(),
                                        muscleGroup = muscle.trim(), sortOrder = templates.count { it.programName == program }))
                                } else {
                                    val old = templates.firstOrNull { it.id == draft.id }
                                    if (old != null) dao.updateTemplate(old.copy(programName = program, name = name.trim(),
                                        muscleGroup = muscle.trim(), sortOrder = if (old.programName == program) old.sortOrder
                                        else templates.count { it.programName == program }))
                                }
                                status = "Изменения сохранены."
                                showEditor = false; editing = null
                            }
                        }) { Text("Сохранить") }
                    }
                }
            }
        }
    }
}



@Composable
private fun ProgramBuilderScreen(
    modifier: Modifier,
    dao: FitnessDao,
    programs: List<TrainingProgram>,
    days: List<ProgramDay>,
    links: List<ProgramDayExercise>,
    templates: List<ExerciseTemplate>,
    status: String,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedProgramId by remember(programs) { mutableStateOf(programs.firstOrNull()?.id) }
    val selectedProgram = programs.firstOrNull { it.id == selectedProgramId }
    val programDays = days.filter { it.programId == selectedProgramId }.sortedBy { it.sortOrder }
    var selectedDayId by remember(programDays) { mutableStateOf(programDays.firstOrNull()?.id) }
    val selectedDay = programDays.firstOrNull { it.id == selectedDayId }
    var showNewProgram by remember { mutableStateOf(false) }
    var showNewDay by remember { mutableStateOf(false) }
    var showExercisePicker by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var expandedProgram by remember { mutableStateOf(false) }
    var expandedDay by remember { mutableStateOf(false) }
    val chosen = links.filter { it.dayId == selectedDayId }.sortedBy { it.sortOrder }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Конструктор программ", style = MaterialTheme.typography.headlineSmall)
        Text("Создай программу на нужный период, добавь тренировочные дни и собери каждый день из общей библиотеки упражнений.")
        ExposedDropdownMenuBox(expandedProgram, { expandedProgram = it }) {
            OutlinedTextField(selectedProgram?.name ?: "Нет программ", {}, readOnly = true,
                label = { Text("Программа") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedProgram) },
                modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expandedProgram, { expandedProgram = false }) {
                programs.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = {
                    selectedProgramId = p.id; expandedProgram = false
                }) }
            }
        }
        Button(onClick = { newName = ""; showNewProgram = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Создать программу") }
        if (selectedProgram != null) {
            ExposedDropdownMenuBox(expandedDay, { expandedDay = it }) {
                OutlinedTextField(selectedDay?.name ?: "Нет дней", {}, readOnly = true,
                    label = { Text("Тренировочный день") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedDay) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expandedDay, { expandedDay = false }) {
                    programDays.forEach { d -> DropdownMenuItem(text = { Text(d.name) }, onClick = {
                        selectedDayId = d.id; expandedDay = false
                    }) }
                }
            }
            OutlinedButton(onClick = { newName = ""; showNewDay = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Добавить тренировочный день") }
        }
        if (selectedDay != null) {
            Text("Упражнения · ${selectedDay.name}", style = MaterialTheme.typography.titleLarge)
            if (chosen.isEmpty()) Text("Пока нет упражнений. Добавь их из библиотеки.")
            chosen.forEachIndexed { index, link ->
                val template = templates.firstOrNull { it.id == link.templateId }
                if (template != null) Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${template.name}", style = MaterialTheme.typography.titleMedium)
                            Text(template.muscleGroup, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { scope.launch { dao.clearDayExercises(selectedDay.id); chosen.filter { it.id != link.id }.forEachIndexed { n, item -> dao.addProgramDayExercise(item.copy(id = 0, sortOrder = n)) } } }) { Text("Убрать") }
                    }
                }
            }
            Button(onClick = { showExercisePicker = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Добавить упражнения из библиотеки") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
    if (showNewProgram) AlertDialog(onDismissRequest = { showNewProgram = false },
        title = { Text("Новая программа") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("Название, например «Месяц 1»") }) },
        confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = {
            scope.launch { val id = dao.addProgram(TrainingProgram(name = newName.trim(), sortOrder = programs.size)); selectedProgramId = id; selectedDayId = null; onStatus("Программа создана.") }
            showNewProgram = false
        }) { Text("Создать") } },
        dismissButton = { TextButton(onClick = { showNewProgram = false }) { Text("Отмена") } })
    if (showNewDay) AlertDialog(onDismissRequest = { showNewDay = false },
        title = { Text("Новый тренировочный день") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("Например, День А — верх тела") }) },
        confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = {
            scope.launch { val id = dao.addProgramDay(ProgramDay(programId = selectedProgram!!.id, name = newName.trim(), sortOrder = programDays.size)); selectedDayId = id; onStatus("Тренировочный день создан.") }
            showNewDay = false
        }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = { showNewDay = false }) { Text("Отмена") } })
    if (showExercisePicker && selectedDay != null) {
        var selectedIds by remember { mutableStateOf(chosen.map { it.templateId }.toSet()) }
        AlertDialog(onDismissRequest = { showExercisePicker = false },
            title = { Text("Выбери упражнения") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    templates.sortedWith(compareBy<ExerciseTemplate> { it.muscleGroup }.thenBy { it.name }).forEach { item ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = item.id in selectedIds, onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                            })
                            Column { Text(item.name); Text(item.muscleGroup, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    dao.clearDayExercises(selectedDay.id)
                    templates.filter { it.id in selectedIds }.forEachIndexed { index, item ->
                        dao.addProgramDayExercise(ProgramDayExercise(dayId = selectedDay.id, templateId = item.id, sortOrder = index))
                    }
                    onStatus("Состав тренировки сохранён.")
                }
                showExercisePicker = false
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { showExercisePicker = false }) { Text("Отмена") } })
    }
}

@Composable
private fun HomeDashboard(
    modifier: Modifier = Modifier,
    nextNumber: Int,
    workoutCount: Int,
    exerciseCount: Int,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onExercises: () -> Unit,
    onAnalytics: () -> Unit,
    onBackup: () -> Unit,
    onProfile: () -> Unit,
    lastWorkout: WorkoutWithExercises?,
    status: String
) {
    val orange = androidx.compose.ui.graphics.Color(0xFFFF8A00)
    val blue = androidx.compose.ui.graphics.Color(0xFF19BCEB)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .then(Modifier.border(2.dp, orange, androidx.compose.foundation.shape.CircleShape))
                        .clip(androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.vanya_avatar),
                        contentDescription = "Портрет Вани в спортивной майке",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("VANYA FITNESS", color = orange, style = MaterialTheme.typography.titleLarge)
                    Text("Привет, Ваня! 💪", style = MaterialTheme.typography.headlineSmall)
                    Text("Продолжаем тренировки!", color = androidx.compose.ui.graphics.Color(0xFFB6C8D5))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("ТРЕНИРОВОК", workoutCount.toString(), "▦", orange, Modifier.weight(1f))
            StatTile("СЛЕДУЮЩАЯ", "№$nextNumber", "↗", blue, Modifier.weight(1f))
            StatTile("УПРАЖНЕНИЙ", exerciseCount.toString(), "✦", orange, Modifier.weight(1f))
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF172F40)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("СЛЕДУЮЩАЯ ТРЕНИРОВКА", color = blue, style = MaterialTheme.typography.labelLarge)
                Text(if (nextNumber % 2 == 0) PROGRAM_B else PROGRAM_A,
                    style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
                Text("Занятие №$nextNumber · Веди подходы прямо во время тренировки",
                    color = androidx.compose.ui.graphics.Color(0xFFB6C8D5), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("НАЧАТЬ ТРЕНИРОВКУ  →") }
            }
        }
        lastWorkout?.let { recent ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("ПОСЛЕДНЯЯ ТРЕНИРОВКА", color = orange, style = MaterialTheme.typography.labelLarge)
                    Text("№" + recent.workout.number + " · " + recent.workout.program, style = MaterialTheme.typography.titleMedium)
                    Text(recent.workout.date, color = androidx.compose.ui.graphics.Color(0xFFB6C8D5))
                    val sets = recent.exercises.sumOf { it.sets.size }
                    val volume = recent.exercises.sumOf { ex -> ex.sets.sumOf { it.weightKg * it.reps } }.toInt()
                    Text(recent.exercises.size.toString() + " упражнений  ·  " + sets + " подходов  ·  " + volume + " кг·повт.",
                        color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardTile("ИСТОРИЯ", "Все занятия", "▤", blue, Modifier.weight(1f), onHistory)
            DashboardTile("ПРОГРЕСС", "Рекорды и графики", "▥", blue, Modifier.weight(1f), onAnalytics)
        }
        Text("Все дополнительные разделы доступны во вкладке «Ещё».",
            color = androidx.compose.ui.graphics.Color(0xFF8298A8), style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) {
            Text(status, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Сильнее с каждой тренировкой", color = androidx.compose.ui.graphics.Color(0xFF8298A8),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MoreMenuScreen(
    modifier: Modifier = Modifier,
    onHistory: () -> Unit,
    onExercises: () -> Unit,
    onAnalytics: () -> Unit,
    onBackup: () -> Unit,
    onProfile: () -> Unit
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ещё", style = MaterialTheme.typography.headlineSmall)
        Text("Дополнительные разделы Vanya Fitness", color = androidx.compose.ui.graphics.Color(0xFFB6C8D5))
        DashboardTile("ИСТОРИЯ ТРЕНИРОВОК", "Все сохранённые занятия и подходы", "▤",
            MaterialTheme.colorScheme.secondary, Modifier.fillMaxWidth(), onHistory)
        DashboardTile("БИБЛИОТЕКА УПРАЖНЕНИЙ", "Программы, порядок и фотографии", "💪",
            MaterialTheme.colorScheme.secondary, Modifier.fillMaxWidth(), onExercises)
        DashboardTile("КОНСТРУКТОР ПРОГРАММ", "Создавай программы и собирай дни из библиотеки", "🧩",\n            MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth(), onPrograms)\n        DashboardTile("ПРОФИЛЬ И ДОСТИЖЕНИЯ", "Параметры, календарь и награды", "🏆",
            MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth(), onProfile)
        DashboardTile("РЕЗЕРВНАЯ КОПИЯ", "Сохранить или перенести историю", "⟲",
            MaterialTheme.colorScheme.secondary, Modifier.fillMaxWidth(), onBackup)
    }
}

@Composable
private fun RestTimerCard() {
    var seconds by remember { mutableIntStateOf(90) }
    var remaining by remember { mutableIntStateOf(90) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(running, remaining) {
        if (running && remaining > 0) {
            delay(1000)
            remaining -= 1
        } else if (remaining == 0) {
            running = false
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⏱ ТАЙМЕР ОТДЫХА", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            Text(String.format(Locale.getDefault(), "%02d:%02d", remaining / 60, remaining % 60),
                style = MaterialTheme.typography.headlineLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 90, 120).forEach { value ->
                    FilterChip(selected = seconds == value, onClick = {
                        seconds = value; remaining = value; running = false
                    }, label = { Text("${value / 60} мин") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (running) running = false else {
                        if (remaining <= 0) remaining = seconds
                        running = true
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (running) "Пауза" else "Старт") }
                OutlinedButton(onClick = { running = false; remaining = seconds }) { Text("Сброс") }
            }
            if (remaining == 0) Text("Отдых завершён — следующий подход!", color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ProfileProgressScreen(modifier: Modifier, workouts: List<WorkoutWithExercises>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vanya_profile", android.content.Context.MODE_PRIVATE) }
    var height by remember { mutableStateOf(prefs.getString("height", "") ?: "") }
    var weight by remember { mutableStateOf(prefs.getString("weight", "") ?: "") }
    var age by remember { mutableStateOf(prefs.getString("age", "") ?: "") }
    val count = workouts.size
    val totalSets = workouts.sumOf { w -> w.exercises.sumOf { it.sets.size } }
    val dates = workouts.map { it.workout.date }.toSet()
    val milestones = listOf(10, 20, 30, 50, 75, 100)
    val lastWorkout = workouts.maxByOrNull { it.workout.number }
    val coaching = when {
        lastWorkout == null -> "Сохрани первую тренировку — здесь появятся персональные подсказки."
        lastWorkout.workout.program == PROGRAM_B -> "Следующая по плану программа — грудь, спина и руки. На плечах сохраняй контроль техники и не гонись за весом."
        else -> "Следующая по плану программа — ноги и плечи. Удели внимание средней и задней дельте, выполняй движения без рывков."
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Профиль Вани", style = MaterialTheme.typography.headlineSmall)
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ЛИЧНЫЕ ПАРАМЕТРЫ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(age, { age = it.filter(Char::isDigit); prefs.edit().putString("age", age).apply() },
                    label = { Text("Возраст, лет") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(height, { height = it.filter(Char::isDigit); prefs.edit().putString("height", height).apply() },
                    label = { Text("Рост, см") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(weight, { weight = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }; prefs.edit().putString("weight", weight).apply() },
                    label = { Text("Вес, кг") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Параметры сохраняются на этом телефоне.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("ТРЕНИРОВОК", count.toString(), "▦", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            StatTile("ПОДХОДОВ", totalSets.toString(), "↗", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
        }
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🏆 ДОСТИЖЕНИЯ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                milestones.forEach { target ->
                    val achieved = count >= target
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (achieved) "🏅" else "🔒", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$target тренировок")
                            LinearProgressIndicator(progress = { (count.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                        Text("$count/$target", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📅 КАЛЕНДАРЬ АКТИВНОСТИ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text("Последние 7 дней", style = MaterialTheme.typography.bodySmall)
                val recent = (0..6).map { offset ->
                    val calendar = java.util.Calendar.getInstance()
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, offset - 6)
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
                    date to dates.contains(date)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    recent.forEach { (date, active) ->
                        Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(date.takeLast(2), color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFF344958),
                                modifier = Modifier.size(22.dp)) {}
                            Text(date.substring(5, 7), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("Отмечены дни, в которые сохранена тренировка.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ПЛАН НА СЛЕДУЮЩУЮ ТРЕНИРОВКУ", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleMedium)
                Text(coaching)
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, symbol: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(symbol, color = accent, style = MaterialTheme.typography.titleLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White)
            Text(title, style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFFB6C8D5), maxLines = 1)
        }
    }
}

@Composable
private fun DashboardTile(title: String, subtitle: String, symbol: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 112.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF102536)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.55f))) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(symbol, color = accent, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = androidx.compose.ui.graphics.Color(0xFFB6C8D5), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyticsScreen(workouts: List<WorkoutWithExercises>) {
    val ordered = workouts.sortedBy { it.workout.number }
    val exerciseNames = ordered.flatMap { it.exercises }.map { it.exercise.name }.distinct()
    val muscleNames = ordered.flatMap { it.exercises }.map { it.exercise.muscleGroup }.distinct()
    var selectedExercise by remember(exerciseNames) { mutableStateOf(exerciseNames.firstOrNull().orEmpty()) }
    var selectedMuscle by remember(muscleNames) { mutableStateOf(muscleNames.firstOrNull().orEmpty()) }
    var exerciseMenu by remember { mutableStateOf(false) }
    var muscleMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Аналитика прогресса", style = MaterialTheme.typography.headlineSmall)
        Text("Рост рабочего веса", style = MaterialTheme.typography.titleMedium)
        if (exerciseNames.isEmpty()) Text("График появится после сохранения тренировок.")
        else {
            ExposedDropdownMenuBox(exerciseMenu, { exerciseMenu = it }) {
                OutlinedTextField(selectedExercise, {}, readOnly = true, label = { Text("Упражнение") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exerciseMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(exerciseMenu, { exerciseMenu = false }) {
                    exerciseNames.forEach { n -> DropdownMenuItem(text = { Text(n) },
                        onClick = { selectedExercise = n; exerciseMenu = false }) }
                }
            }
            val data = ordered.mapNotNull { w ->
                val weights = w.exercises.filter { it.exercise.name == selectedExercise }.flatMap { it.sets }.map { it.weightKg.toFloat() }
                if (weights.isEmpty()) null else w.workout.number to (weights.maxOrNull() ?: 0f)
            }
            if (data.isEmpty()) Text("Нет записей по выбранному упражнению.")
            else {
                Text("Максимальный вес, кг", style = MaterialTheme.typography.labelMedium)
                SimpleLineChart(data.map { it.second })
                Text(data.joinToString("   ") { "№${it.first}: ${it.second} кг" }, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        HorizontalDivider()
        Text("ЛИЧНЫЕ РЕКОРДЫ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        val records = ordered.flatMap { workout -> workout.exercises.mapNotNull { ex ->
            val best = ex.sets.maxByOrNull { it.weightKg }
            if (best == null) null else Triple(ex.exercise.name, best.weightKg, best.reps)
        } }.groupBy { it.first }.mapValues { (_, entries) -> entries.maxBy { it.second } }
        if (records.isEmpty()) Text("Рекорды появятся после сохранения подходов.")
        else records.toList().sortedBy { it.first }.forEach { (name, record) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, modifier = Modifier.weight(1f))
                Text("${record.second} кг × ${record.third}", color = MaterialTheme.colorScheme.secondary)
            }
        }
        HorizontalDivider()
        Text("АКЦЕНТ: РАЗВИТИЕ ПЛЕЧ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        val shoulderVolume = ordered.mapNotNull { workout ->
            val volume = workout.exercises.filter { it.exercise.muscleGroup.contains("Плеч", true) }
                .sumOf { ex -> ex.sets.sumOf { it.weightKg * it.reps } }
            if (volume > 0) workout.workout.number to volume.toFloat() else null
        }
        if (shoulderVolume.isEmpty()) Text("Данные появятся после сохранения программы «Ноги и плечи».")
        else {
            Text("Объём работы на плечи: вес × повторения")
            SimpleLineChart(shoulderVolume.map { it.second })
            Text(shoulderVolume.joinToString("   ") { "№${it.first}: ${it.second.toInt()} кг" }, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("НАГРУЗКА ПО ГРУППАМ МЫШЦ", style = MaterialTheme.typography.titleMedium)
        val groupVolumes = ordered.flatMap { it.exercises }.groupBy { it.exercise.muscleGroup }
            .mapValues { (_, exercises) -> exercises.sumOf { ex -> ex.sets.sumOf { it.weightKg * it.reps } } }
            .toList().sortedByDescending { it.second }
        groupVolumes.forEach { (group, volume) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(group)
                    Text("${volume.toInt()} кг·повт.", color = MaterialTheme.colorScheme.secondary)
                }
                LinearProgressIndicator(progress = { (volume / (groupVolumes.firstOrNull()?.second?.coerceAtLeast(1.0) ?: 1.0)).toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
        Text("График целевых мышц", style = MaterialTheme.typography.titleMedium)
        Text("Объём нагрузки: вес × повторения", style = MaterialTheme.typography.bodySmall)
        if (muscleNames.isEmpty()) Text("Группы мышц появятся после сохранения тренировок.")
        else {
            ExposedDropdownMenuBox(muscleMenu, { muscleMenu = it }) {
                OutlinedTextField(selectedMuscle, {}, readOnly = true, label = { Text("Группа мышц") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(muscleMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(muscleMenu, { muscleMenu = false }) {
                    muscleNames.forEach { m -> DropdownMenuItem(text = { Text(m) },
                        onClick = { selectedMuscle = m; muscleMenu = false }) }
                }
            }
            val data = ordered.mapNotNull { w ->
                val total = w.exercises.filter { it.exercise.muscleGroup.equals(selectedMuscle, true) }
                    .sumOf { ex -> ex.sets.sumOf { it.weightKg * it.reps } }.toFloat()
                if (total <= 0f) null else w.workout.number to total
            }
            if (data.isEmpty()) Text("Пока нет данных по этой группе мышц.")
            else {
                SimpleLineChart(data.map { it.second })
                Text(data.joinToString("   ") { "№${it.first}: ${it.second.toInt()} кг" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Графики строятся по сохранённым тренировкам. История не изменяется.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SimpleLineChart(values: List<Float>) {
    if (values.isEmpty()) return
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(180.dp).padding(vertical = 8.dp)) {
        val l = 8f; val r = size.width - 8f; val t = 10f; val b = size.height - 10f
        repeat(4) { i ->
            val y = t + (b - t) * i / 3f
            drawLine(grid, androidx.compose.ui.geometry.Offset(l, y),
                androidx.compose.ui.geometry.Offset(r, y), 1.dp.toPx())
        }
        val low = (values.minOrNull() ?: 0f).let { if (it > 0f) it * .85f else 0f }
        val high = (values.maxOrNull() ?: 1f).let { if (it > low) it * 1.08f else low + 1f }
        val pts = values.mapIndexed { i, v ->
            val x = if (values.size == 1) (l + r) / 2f else l + (r - l) * i / (values.size - 1).toFloat()
            val y = b - (v - low) / (high - low) * (b - t)
            androidx.compose.ui.geometry.Offset(x, y)
        }
        if (pts.size > 1) {
            val p = Path().apply { moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(p, line, style = Stroke(3.dp.toPx()))
        }
        pts.forEach { drawCircle(line, 5.dp.toPx(), it) }
    }
}

private fun today(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
