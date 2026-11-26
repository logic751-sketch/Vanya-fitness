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

@Entity(tableName = "training_programs")
data class TrainingProgram(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val sortOrder: Int = 0
)

@Entity(tableName = "program_days", indices = [Index("programId")])
data class ProgramDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programId: Long,
    val name: String,
    val sortOrder: Int = 0
)

@Entity(tableName = "program_day_exercises", indices = [Index("dayId"), Index("templateId")])
data class ProgramDayExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val templateId: Long,
    val sortOrder: Int = 0
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


    @Query("SELECT * FROM training_programs ORDER BY sortOrder, id")
    fun observePrograms(): Flow<List<TrainingProgram>>

    @Query("SELECT * FROM program_days ORDER BY programId, sortOrder, id")
    fun observeProgramDays(): Flow<List<ProgramDay>>

    @Query("SELECT * FROM program_day_exercises ORDER BY dayId, sortOrder, id")
    fun observeProgramDayExercises(): Flow<List<ProgramDayExercise>>

    @Query("SELECT * FROM training_programs ORDER BY id")
    suspend fun allPrograms(): List<TrainingProgram>

    @Query("SELECT * FROM program_days ORDER BY id")
    suspend fun allProgramDays(): List<ProgramDay>

    @Query("SELECT * FROM program_day_exercises ORDER BY id")
    suspend fun allProgramDayExercises(): List<ProgramDayExercise>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(items: List<TrainingProgram>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgramDays(items: List<ProgramDay>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgramDayExercises(items: List<ProgramDayExercise>)

    @Insert
    suspend fun addProgram(item: TrainingProgram): Long

    @Insert
    suspend fun addProgramDay(item: ProgramDay): Long

    @Insert
    suspend fun addProgramDayExercise(item: ProgramDayExercise): Long

    @Update
    suspend fun updateProgram(item: TrainingProgram)

    @Update
    suspend fun updateProgramDay(item: ProgramDay)

    @Query("DELETE FROM program_day_exercises WHERE dayId = :dayId")
    suspend fun clearDayExercises(dayId: Long)

    @Query("DELETE FROM program_days WHERE programId = :programId")
    suspend fun clearProgramDays(programId: Long)

    @Query("DELETE FROM training_programs")
    suspend fun clearPrograms()

    @Query("DELETE FROM program_days")
    suspend fun clearAllProgramDays()

    @Query("DELETE FROM program_day_exercises")
    suspend fun clearAllProgramDayExercises()

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
    entities = [Workout::class, Exercise::class, TrainingSet::class, ExerciseTemplate::class, TrainingProgram::class, ProgramDay::class, ProgramDayExercise::class],
    version = 3,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS training_programs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, sortOrder INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS program_days (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, programId INTEGER NOT NULL, name TEXT NOT NULL, sortOrder INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_program_days_programId ON program_days(programId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS program_day_exercises (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dayId INTEGER NOT NULL, templateId INTEGER NOT NULL, sortOrder INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_program_day_exercises_dayId ON program_day_exercises(dayId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_program_day_exercises_templateId ON program_day_exercises(templateId)")
            }
        }

        @Volatile private var instance: FitnessDatabase? = null

        fun get(context: Context): FitnessDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FitnessDatabase::class.java,
                "vanya_fitness.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
