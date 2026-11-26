package ru.vanyafitness.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: Int,
    val date: String,
    val program: String
)

@Entity(tableName = "exercises", foreignKeys = [ForeignKey(
    entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index("workoutId")])
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val name: String,
    val muscleGroup: String
)

@Entity(tableName = "sets", foreignKeys = [ForeignKey(
    entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index("exerciseId")])
data class TrainingSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int
)

@Entity(tableName = "exercise_templates", indices = [Index(value = ["programName", "sortOrder"])])
data class ExerciseTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programName: String,
    val name: String,
    val muscleGroup: String,
    val imageUri: String? = null,
    val sortOrder: Int = 0
)

data class ExerciseWithSets(
    @Embedded val exercise: Exercise,
    @Relation(parentColumn = "id", entityColumn = "exerciseId") val sets: List<TrainingSet>
)

data class WorkoutWithExercises(
    @Embedded val workout: Workout,
    @Relation(entity = Exercise::class, parentColumn = "id", entityColumn = "workoutId")
    val exercises: List<ExerciseWithSets>
)

@Dao
interface FitnessDao {
    @Transaction
    @Query("SELECT * FROM workouts ORDER BY number DESC")
    fun observeAll(): Flow<List<WorkoutWithExercises>>

    @Query("SELECT MAX(number) FROM workouts")
    suspend fun maxNumber(): Int?

    @Query("SELECT COUNT(*) FROM workouts")
    suspend fun count(): Int

    @Query("SELECT * FROM workouts ORDER BY id")
    suspend fun allWorkouts(): List<Workout>

    @Query("SELECT * FROM exercises ORDER BY id")
    suspend fun allExercises(): List<Exercise>

    @Query("SELECT * FROM sets ORDER BY id")
    suspend fun allSets(): List<TrainingSet>

    @Query("SELECT * FROM exercise_templates ORDER BY id")
    suspend fun allTemplates(): List<ExerciseTemplate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkouts(items: List<Workout>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercises(items: List<Exercise>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSets(items: List<TrainingSet>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(items: List<ExerciseTemplate>)

    @Query("DELETE FROM sets")
    suspend fun clearSets()

    @Query("DELETE FROM exercises")
    suspend fun clearExercises()

    @Query("DELETE FROM workouts")
    suspend fun clearWorkouts()

    @Query("DELETE FROM exercise_templates")
    suspend fun clearTemplates()


    @Query("SELECT * FROM exercise_templates ORDER BY programName, sortOrder, id")
    fun observeTemplates(): Flow<List<ExerciseTemplate>>

    @Query("SELECT COUNT(*) FROM exercise_templates")
    suspend fun countTemplates(): Int

    @Query("SELECT * FROM exercise_templates WHERE programName = :program ORDER BY sortOrder, id")
    suspend fun templatesFor(program: String): List<ExerciseTemplate>

    @Insert
    suspend fun addWorkout(item: Workout): Long

    @Insert
    suspend fun addExercise(item: Exercise): Long

    @Insert
    suspend fun addSets(items: List<TrainingSet>)

    @Insert
    suspend fun addTemplate(item: ExerciseTemplate): Long

    @Update
    suspend fun updateTemplate(item: ExerciseTemplate)

    @Delete
    suspend fun deleteTemplate(item: ExerciseTemplate)
}

@Database(
    entities = [Workout::class, Exercise::class, TrainingSet::class, ExerciseTemplate::class],
    version = 2,
    exportSchema = false
)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun dao(): FitnessDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS exercise_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        programName TEXT NOT NULL,
                        name TEXT NOT NULL,
                        muscleGroup TEXT NOT NULL,
                        imageUri TEXT,
                        sortOrder INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_templates_programName_sortOrder ON exercise_templates(programName, sortOrder)")
            }
        }

        @Volatile private var instance: FitnessDatabase? = null

        fun get(context: Context): FitnessDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FitnessDatabase::class.java,
                "vanya_fitness.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
