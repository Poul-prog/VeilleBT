package com.martin.veillebt.di // Exemple de package

import android.content.Context
import androidx.room.Room
import com.martin.veillebt.data.local.db.AppDatabase // Votre classe de base de données Room
import com.martin.veillebt.data.local.db.BraceletDao // Votre DAO
import com.martin.veillebt.data.repository.BraceletRepository
import com.martin.veillebt.data.repository.BraceletRepositoryImpl // Votre implémentation concrète
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class) // Disponible pendant toute la durée de vie de l'application
object AppModule {

    @Provides
    @Singleton // Pour s'assurer qu'il n'y a qu'une seule instance de la base de données
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "veille_bt_database" // Nom de votre base de données
        )
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    @Singleton // Souvent, les DAOs sont aussi des singletons via la base de données
    fun provideBraceletDao(appDatabase: AppDatabase): BraceletDao {
        return appDatabase.braceletDao()
    }

    @Provides
    @Singleton // Les Repositories sont souvent des singletons
    fun provideBraceletRepository(braceletDao: BraceletDao): BraceletRepository {
        // Si BraceletRepository est une interface, retournez son implémentation
        return BraceletRepositoryImpl(braceletDao)
    }

    // Ajoutez d'autres @Provides pour d'autres dépendances ici
    // (par exemple, client Retrofit, SharedPreferences, etc.)
}
