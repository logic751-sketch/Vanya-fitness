package ru.vanyafitness.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workouts")
data class Workout(@PrimaryKey(autoGenerate = true) val id: Long = 0, val number: Int, val date: String, val program: String)

@Entity(tableName = "exercises", foreignKeys = [ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE)], indices = [Index("workoutId")])
data class Exercise(@PrimaryKey(autoGenerate = true) val id: Long = 0, val workoutId: Long, val name: String, val muscleGroup: String)

@Entity(tableName = "sets", foreignKeys = [ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE)], indices = [Index("exerciseId")])
data class TrainingSet(@PrimaryKey(autoGenerate = true) val id: Long = 0, val exerciseId: Long, val setNumber: Int, val weightKg: Double, val reps: Int)

data class ExerciseWithSets(@Embedded val exercise: Exercise, @Relation(parentColumn = "id", entityColumn = "exerciseId") val sets: List<TrainingSet>)
data class WorkoutWithExercises(@Embedded val workout: Workout, @Relation(entity = Exercise::class, parentColumn = "id", entityColumn = "workoutId") val exercises: List<ExerciseWithSets>)

@Dao
interface FitnessDao {
    @Transaction @Query("SELECT * FROM workouts ORDER BY number DESC") fun observeAll(): Flow<List<WorkoutWithExercises>>
    @Query("SELECT MAX(number) FROM workouts") suspend fun maxNumber(): Int?
    @Query("SELECT COUNT(*) FROM workouts") suspend fun count(): Int
    @Insert suspend fun addWorkout(item: Workout): Long
    @Insert suspend fun addExercise(item: Exercise): Long
    @Insert suspend fun addSets(items: List<TrainingSet>)
}

@Database(entities = [Workout::class, Exercise::class, TrainingSet::class], version = 1, exportSchema = false)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun dao(): FitnessDao
    companion object {
        @Volatile private var instance: FitnessDatabase? = null
        fun get(context: Context): FitnessDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FitnessDatabase::class.java, "vanya_fitness.db").build().also { instance = it }
        }
    }
}
