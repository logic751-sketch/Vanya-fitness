package ru.vanyafitness.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64

/**
 * Portable JSON backup for all workout history and the exercise library.
 * The database is replaced only after the complete backup has been parsed.
 */
object BackupManager {
    private const val FORMAT = "vanya-fitness-backup"
    private const val VERSION = 2

    suspend fun export(context: Context, db: FitnessDatabase, uri: Uri) {
        val dao = db.dao()
        val workouts = dao.allWorkouts()
        val exercises = dao.allExercises()
        val sets = dao.allSets()
        val templates = dao.allTemplates()

        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("workouts", JSONArray().apply {
                workouts.forEach { w -> put(JSONObject()
                    .put("id", w.id).put("number", w.number)
                    .put("date", w.date).put("program", w.program)) }
            })
            .put("exercises", JSONArray().apply {
                exercises.forEach { e -> put(JSONObject()
                    .put("id", e.id).put("workoutId", e.workoutId)
                    .put("name", e.name).put("muscleGroup", e.muscleGroup)) }
            })
            .put("sets", JSONArray().apply {
                sets.forEach { s -> put(JSONObject()
                    .put("id", s.id).put("exerciseId", s.exerciseId)
                    .put("setNumber", s.setNumber).put("weightKg", s.weightKg)
                    .put("reps", s.reps)) }
            })
            .put("programs", JSONArray().apply {
                dao.allPrograms().forEach { p -> put(JSONObject()
                    .put("id", p.id).put("name", p.name).put("description", p.description)
                    .put("sortOrder", p.sortOrder)) }
            })
            .put("programDays", JSONArray().apply {
                dao.allProgramDays().forEach { d -> put(JSONObject()
                    .put("id", d.id).put("programId", d.programId).put("name", d.name)
                    .put("sortOrder", d.sortOrder)) }
            })
            .put("programDayExercises", JSONArray().apply {
                dao.allProgramDayExercises().forEach { e -> put(JSONObject()
                    .put("id", e.id).put("dayId", e.dayId).put("templateId", e.templateId)
                    .put("sortOrder", e.sortOrder)) }
            })
            .put("templates", JSONArray().apply {
                templates.forEach { t -> put(JSONObject()
                    .put("id", t.id).put("programName", t.programName)
                    .put("name", t.name).put("muscleGroup", t.muscleGroup)
                     .put("imageUri", t.imageUri ?: JSONObject.NULL)
                    .put("imageBase64", encodePhoto(context, t.imageUri) ?: JSONObject.NULL)
                    .put("sortOrder", t.sortOrder)) }
            })

        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(root.toString(2))
        } ?: error("Не удалось открыть файл для записи.")
    }

    suspend fun restore(context: Context, db: FitnessDatabase, uri: Uri) {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: error("Не удалось прочитать файл.")
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "Это не резервная копия Vanya Fitness." }
        val backupVersion = root.optInt("version", -1)
        require(backupVersion in 1..VERSION) { "Версия резервной копии не поддерживается." }

        val workouts = root.getJSONArray("workouts").toWorkouts()
        val exercises = root.getJSONArray("exercises").toExercises()
        val sets = root.getJSONArray("sets").toSets()
        val templateArray = root.getJSONArray("templates")
        val programs = if (backupVersion >= 2) root.getJSONArray("programs").toPrograms() else emptyList()
        val programDays = if (backupVersion >= 2) root.getJSONArray("programDays").toProgramDays() else emptyList()
        val dayExercises = if (backupVersion >= 2) root.getJSONArray("programDayExercises").toProgramDayExercises() else emptyList()
        val photoFolder = java.io.File(context.filesDir, "exercise_photos").apply { mkdirs() }
        val templates = templateArray.toTemplates().map { template ->
            val encoded = (0 until templateArray.length()).map { templateArray.getJSONObject(it) }
                .firstOrNull { it.getLong("id") == template.id }
                ?.let { it.optString("imageBase64").takeIf { value -> value.isNotBlank() && value != "null" } }
            if (encoded != null) {
                val photo = java.io.File(photoFolder, "exercise_" + template.id + ".jpg")
                photo.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                template.copy(imageUri = Uri.fromFile(photo).toString())
            } else template
        }

        val workoutIds = workouts.map { it.id }.toSet()
        val exerciseIds = exercises.map { it.id }.toSet()
        require(exercises.all { it.workoutId in workoutIds }) { "В копии найдены упражнения без тренировки." }
        require(sets.all { it.exerciseId in exerciseIds }) { "В копии найдены подходы без упражнения." }

        val programIds = programs.map { it.id }.toSet()
        val dayIds = programDays.map { it.id }.toSet()
        val templateIds = templates.map { it.id }.toSet()
        require(programDays.all { it.programId in programIds }) { "В копии найдены дни без программы." }
        require(dayExercises.all { it.dayId in dayIds && it.templateId in templateIds }) { "В копии найдены упражнения без программы или дня." }

        db.withTransaction {
            val dao = db.dao()
            dao.clearSets()
            dao.clearExercises()
            dao.clearWorkouts()
            dao.clearTemplates()
            dao.clearAllProgramDayExercises()
            dao.clearAllProgramDays()
            dao.clearPrograms()
            dao.insertTemplates(templates)
            dao.insertPrograms(programs)
            dao.insertProgramDays(programDays)
            dao.insertProgramDayExercises(dayExercises)
            dao.insertWorkouts(workouts)
            dao.insertExercises(exercises)
            dao.insertSets(sets)
        }
    }

    private fun encodePhoto(context: Context, imageUri: String?): String? {
        if (imageUri.isNullOrBlank()) return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { input ->
                Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
            }
        } catch (_: Exception) { null }
    }

    private fun JSONArray.toPrograms() = (0 until length()).map { i ->
        getJSONObject(i).let { TrainingProgram(it.getLong("id"), it.getString("name"),
            it.optString("description"), it.optInt("sortOrder")) }
    }
    private fun JSONArray.toProgramDays() = (0 until length()).map { i ->
        getJSONObject(i).let { ProgramDay(it.getLong("id"), it.getLong("programId"),
            it.getString("name"), it.optInt("sortOrder")) }
    }
    private fun JSONArray.toProgramDayExercises() = (0 until length()).map { i ->
        getJSONObject(i).let { ProgramDayExercise(it.getLong("id"), it.getLong("dayId"),
            it.getLong("templateId"), it.optInt("sortOrder")) }
    }
    private fun JSONArray.toWorkouts() = (0 until length()).map { i ->
        getJSONObject(i).let { Workout(it.getLong("id"), it.getInt("number"), it.getString("date"), it.getString("program")) }
    }
    private fun JSONArray.toExercises() = (0 until length()).map { i ->
        getJSONObject(i).let { Exercise(it.getLong("id"), it.getLong("workoutId"), it.getString("name"), it.getString("muscleGroup")) }
    }
    private fun JSONArray.toSets() = (0 until length()).map { i ->
        getJSONObject(i).let { TrainingSet(it.getLong("id"), it.getLong("exerciseId"), it.getInt("setNumber"), it.getDouble("weightKg"), it.getInt("reps")) }
    }
    private fun JSONArray.toTemplates() = (0 until length()).map { i ->
        getJSONObject(i).let { t ->
            ExerciseTemplate(
                id = t.getLong("id"),
                programName = t.getString("programName"),
                name = t.getString("name"),
                muscleGroup = t.getString("muscleGroup"),
                imageUri = if (t.isNull("imageUri")) null else t.getString("imageUri"),
                sortOrder = t.getInt("sortOrder")
            )
        }
    }
}
