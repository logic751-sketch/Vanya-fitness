package ru.vanyafitness.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable JSON backup for all workout history and the exercise library.
 * The database is replaced only after the complete backup has been parsed.
 */
object BackupManager {
    private const val FORMAT = "vanya-fitness-backup"
    private const val VERSION = 1

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
            .put("templates", JSONArray().apply {
                templates.forEach { t -> put(JSONObject()
                    .put("id", t.id).put("programName", t.programName)
                    .put("name", t.name).put("muscleGroup", t.muscleGroup)
                    .put("imageUri", t.imageUri ?: JSONObject.NULL)
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
        require(root.optInt("version", -1) == VERSION) { "Версия резервной копии не поддерживается." }

        val workouts = root.getJSONArray("workouts").toWorkouts()
        val exercises = root.getJSONArray("exercises").toExercises()
        val sets = root.getJSONArray("sets").toSets()
        val templates = root.getJSONArray("templates").toTemplates()

        val workoutIds = workouts.map { it.id }.toSet()
        val exerciseIds = exercises.map { it.id }.toSet()
        require(exercises.all { it.workoutId in workoutIds }) { "В копии найдены упражнения без тренировки." }
        require(sets.all { it.exerciseId in exerciseIds }) { "В копии найдены подходы без упражнения." }

        db.withTransaction {
            val dao = db.dao()
            dao.clearSets()
            dao.clearExercises()
            dao.clearWorkouts()
            dao.clearTemplates()
            dao.insertTemplates(templates)
            dao.insertWorkouts(workouts)
            dao.insertExercises(exercises)
            dao.insertSets(sets)
        }
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
