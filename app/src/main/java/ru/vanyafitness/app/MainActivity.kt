package ru.vanyafitness.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        lifecycleScope.launch { seedTemplates(dao); importWorkout19(dao) }
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

    var page by remember { mutableIntStateOf(0) }
    var selectedProgram by remember { mutableStateOf(PROGRAM_A) }
    var workoutDate by remember { mutableStateOf(today()) }
    var setDrafts by remember { mutableStateOf<Map<Long, List<SetDraft>>>(emptyMap()) }
    var status by remember { mutableStateOf("") }
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
                    listOf("Главная", "Тренировка", "Упражнения", "История", "Аналитика", "Копия").forEachIndexed { index, label ->
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
